package server.nadeliv.travel.service.serviceImpl;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import server.nadeliv.error.CustomException;
import server.nadeliv.error.ErrorCode;
import server.nadeliv.travel.model.dto.MediaDownloadTicketResponse;
import server.nadeliv.travel.model.dto.MediaDownloadUrlResponse;
import server.nadeliv.travel.model.entities.TravelMedia;
import server.nadeliv.travel.model.entities.Travels;
import server.nadeliv.travel.model.enums.TravelVisibility;
import server.nadeliv.travel.repo.TravelMediaRepo;
import server.nadeliv.travel.repo.TravelUsersRepo;
import server.nadeliv.travel.repo.TravelsRepo;
import server.nadeliv.travel.service.S3Service;
import server.nadeliv.travel.service.TravelMediaDownloadService;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class TravelMediaDownloadServiceImpl implements TravelMediaDownloadService {

    private final TravelsRepo travelsRepo;
    private final TravelUsersRepo travelUsersRepo;
    private final TravelMediaRepo travelMediaRepo;
    private final S3Service s3Service;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String TICKET_KEY_PREFIX = "travel:download:";
    // 브라우저가 링크를 열기까지의 여유. 한 번 쓰고 지우지 않는다 — 다운로드 관리자·재시도가 같은 URL 을 다시 부른다
    private static final Duration TICKET_TTL = Duration.ofMinutes(10);
    private static final Duration URL_TTL = Duration.ofMinutes(10);
    public static final int MAX_BATCH_FILES = 1000;
    private static final String PUBLIC_PATH_PREFIX = "api/v1/travels/ps/downloads/";

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Redis 에 저장되는 티켓 내용 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Ticket(String travelId, String userId, List<String> mediaIds, String fileName) {}

    // ==================== 단일 파일 ====================

    @Override
    public MediaDownloadUrlResponse createDownloadUrl(String travelId, String mediaId, String userId) {
        Travels travel = findTravel(travelId);
        validateTravelAccess(travel, userId);

        TravelMedia media = travelMediaRepo.findById(mediaId)
                .filter(m -> travelId.equals(m.getTravelId()))
                .orElseThrow(() -> new CustomException(ErrorCode.TRAVEL_MEDIA_NOT_FOUND));

        String fileName = downloadName(media);
        String url = s3Service.presignGet(media.getFileUrl(), fileName, media.getMimeType(), URL_TTL);
        return MediaDownloadUrlResponse.builder()
                .url(url)
                .fileName(fileName)
                .expiresAt(LocalDateTime.now().plus(URL_TTL))
                .build();
    }

    // ==================== 일괄 ZIP ====================

    @Override
    public MediaDownloadTicketResponse createTicket(String travelId, List<String> mediaIds, String userId) {
        Travels travel = findTravel(travelId);
        validateTravelAccess(travel, userId);

        List<String> ids = new ArrayList<>(new LinkedHashSet<>(mediaIds));
        if (ids.size() > MAX_BATCH_FILES) {
            throw new CustomException(ErrorCode.TRAVEL_DOWNLOAD_TOO_MANY);
        }
        List<TravelMedia> mediaList = resolveMedia(travelId, ids);

        String zipName = zipFileName(travel);
        String ticket = newTicket();
        try {
            String json = objectMapper.writeValueAsString(
                    new Ticket(travelId, userId, mediaList.stream().map(TravelMedia::getId).toList(), zipName));
            redisTemplate.opsForValue().set(TICKET_KEY_PREFIX + ticket, json, TICKET_TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }

        long totalBytes = mediaList.stream().mapToLong(m -> m.getFileSize() != null ? m.getFileSize() : 0L).sum();
        log.info("Download ticket issued: travel={} user={} files={} bytes={}", travelId, userId, mediaList.size(), totalBytes);
        return MediaDownloadTicketResponse.builder()
                .ticket(ticket)
                .path(PUBLIC_PATH_PREFIX + ticket)
                .fileName(zipName)
                .fileCount(mediaList.size())
                .totalBytes(totalBytes)
                .expiresAt(LocalDateTime.now().plus(TICKET_TTL))
                .build();
    }

    @Override
    public void streamZip(String ticket, HttpServletResponse response) {
        if (ticket == null || !ticket.matches("[A-Za-z0-9_-]{32,64}")) {
            throw new CustomException(ErrorCode.TRAVEL_DOWNLOAD_TICKET_INVALID);
        }
        String json = redisTemplate.opsForValue().get(TICKET_KEY_PREFIX + ticket);
        if (json == null) {
            throw new CustomException(ErrorCode.TRAVEL_DOWNLOAD_TICKET_INVALID);
        }
        Ticket t;
        try {
            t = objectMapper.readValue(json, Ticket.class);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.TRAVEL_DOWNLOAD_TICKET_INVALID);
        }
        // 티켓 발급 후 미디어가 지워졌을 수 있으니 다시 조회 (없는 것은 건너뜀)
        List<TravelMedia> mediaList = resolveMedia(t.travelId(), t.mediaIds());
        writeZip(mediaList, t.fileName(), response);
    }

    @Override
    public void streamZip(String travelId, List<String> mediaIds, String userId, HttpServletResponse response) {
        Travels travel = findTravel(travelId);
        validateTravelAccess(travel, userId);
        List<TravelMedia> mediaList = resolveMedia(travelId, new ArrayList<>(new LinkedHashSet<>(mediaIds)));
        writeZip(mediaList, zipFileName(travel), response);
    }

    /**
     * S3 객체를 하나씩 받아 그대로 ZIP 엔트리로 흘려보낸다. 메모리에는 버퍼 한 조각만 머문다.
     * 사진·영상은 이미 압축된 포맷이라 deflate 를 끈다 (CPU 절약, 속도 ↑).
     */
    private void writeZip(List<TravelMedia> mediaList, String zipName, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(zipName));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        // 길이를 모르므로 chunked 로 간다. ELB idle timeout 은 데이터가 계속 흐르는 한 걸리지 않는다

        Set<String> usedNames = new HashSet<>();
        long written = 0;
        try (OutputStream out = response.getOutputStream();
             ZipOutputStream zos = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zos.setLevel(Deflater.NO_COMPRESSION);
            for (TravelMedia media : mediaList) {
                String entryName = uniqueName(downloadName(media), usedNames);
                try (ResponseInputStream<GetObjectResponse> s3Object = s3Service.downloadFile(media.getFileUrl())) {
                    ZipEntry entry = new ZipEntry(entryName);
                    if (media.getCreated() != null) {
                        entry.setTimeLocal(media.getCreated());
                    }
                    zos.putNextEntry(entry);
                    written += s3Object.transferTo(zos);
                    zos.closeEntry();
                } catch (CustomException e) {
                    // 원본이 사라진 파일은 건너뛰고 나머지는 계속 내려준다
                    log.warn("ZIP entry skipped (S3 error) media={}: {}", media.getId(), e.getMessage());
                }
                zos.flush();
            }
            zos.finish();
            log.info("ZIP streamed: files={} bytes={}", mediaList.size(), written);
        } catch (IOException e) {
            // 대부분 클라이언트가 중간에 취소한 경우. 헤더가 이미 나갔으니 상태를 바꿀 수 없다
            log.info("ZIP stream aborted after {} bytes: {}", written, e.getMessage());
        }
    }

    // ==================== helpers ====================

    /** 요청 순서를 유지하면서 이 여행에 속한 미디어만 돌려준다 */
    private List<TravelMedia> resolveMedia(String travelId, List<String> ids) {
        Map<String, TravelMedia> byId = new HashMap<>();
        for (TravelMedia m : travelMediaRepo.findAllById(ids)) {
            if (travelId.equals(m.getTravelId()) && m.getFileUrl() != null) {
                byId.put(m.getId(), m);
            }
        }
        List<TravelMedia> ordered = new ArrayList<>();
        for (String id : ids) {
            TravelMedia m = byId.get(id);
            if (m != null) ordered.add(m);
        }
        if (ordered.isEmpty()) {
            throw new CustomException(ErrorCode.TRAVEL_MEDIA_NOT_FOUND);
        }
        return ordered;
    }

    private static String downloadName(TravelMedia media) {
        String name = media.getOriginalFileName() != null && !media.getOriginalFileName().isBlank()
                ? media.getOriginalFileName()
                : media.getFileName();
        // 경로 구분자·제어문자는 ZIP 안에서 폴더가 되거나 깨지므로 제거
        return name.replaceAll("[\\\\/\\p{Cntrl}]", "_");
    }

    /** 같은 이름이 또 나오면 "name (2).ext" 식으로 — ZipOutputStream 은 중복 엔트리에서 예외를 던진다 */
    private static String uniqueName(String name, Set<String> used) {
        if (used.add(name)) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) {
            String candidate = base + " (" + i + ")" + ext;
            if (used.add(candidate)) return candidate;
        }
    }

    private static String zipFileName(Travels travel) {
        String title = travel.getTitle() != null ? travel.getTitle().trim() : "";
        title = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").trim();
        if (title.length() > 60) title = title.substring(0, 60).trim();
        return (title.isEmpty() ? "travel" : title) + "-media.zip";
    }

    private static String contentDisposition(String fileName) {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        // ASCII 폴백 + RFC 5987 UTF-8 이름
        return "attachment; filename=\"travel-media.zip\"; filename*=UTF-8''" + encoded;
    }

    private static String newTicket() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private Travels findTravel(String travelId) {
        return travelsRepo.findById(travelId)
                .orElseThrow(() -> new CustomException(ErrorCode.TRAVEL_NOT_FOUND));
    }

    // TravelServiceImpl.validateTravelAccess 와 같은 규칙: PUBLIC 이면 누구나, 아니면 멤버만
    private void validateTravelAccess(Travels travel, String userId) {
        if (travel.getVisibility() == TravelVisibility.PUBLIC) {
            return;
        }
        if (userId == null || !travelUsersRepo.existsByTravelIdAndUserId(travel.getId(), userId)) {
            throw new CustomException(ErrorCode.UNAUTHORIZED_TRAVEL_ACCESS);
        }
    }
}
