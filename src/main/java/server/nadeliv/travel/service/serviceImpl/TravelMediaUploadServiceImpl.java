package server.nadeliv.travel.service.serviceImpl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import server.nadeliv.common.service.RateLimitService;
import server.nadeliv.error.CustomException;
import server.nadeliv.error.ErrorCode;
import server.nadeliv.travel.model.dto.MediaUploadInitRequest;
import server.nadeliv.travel.model.dto.MediaUploadInitResponse;
import server.nadeliv.travel.model.dto.MediaUploadPart;
import server.nadeliv.travel.model.entities.TravelMedia;
import server.nadeliv.travel.model.entities.TravelMediaUpload;
import server.nadeliv.travel.model.entities.TravelUsers;
import server.nadeliv.travel.model.enums.MediaUploadMethod;
import server.nadeliv.travel.model.enums.MediaUploadStatus;
import server.nadeliv.travel.model.enums.TravelRole;
import server.nadeliv.travel.repo.TravelMediaRepo;
import server.nadeliv.travel.repo.TravelMediaUploadRepo;
import server.nadeliv.travel.repo.TravelUsersRepo;
import server.nadeliv.travel.repo.TravelsRepo;
import server.nadeliv.travel.service.S3Service;
import server.nadeliv.travel.service.TravelMediaUploadService;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TravelMediaUploadServiceImpl implements TravelMediaUploadService {

    private final TravelsRepo travelsRepo;
    private final TravelUsersRepo travelUsersRepo;
    private final TravelMediaRepo travelMediaRepo;
    private final TravelMediaUploadRepo uploadRepo;
    private final S3Service s3Service;
    private final RateLimitService rateLimitService;
    private final MongoTemplate mongoTemplate;

    // TravelServiceImpl.uploadMedia 와 같은 쿼터를 공유 (사용자당 하루 500개)
    private static final String SCOPE_TRAVEL_UPLOAD = "travel:upload";
    private static final int MAX_DAILY_UPLOAD = 500;

    private static final long MB = 1024L * 1024L;
    public static final long MAX_FILE_SIZE = 5L * 1024L * MB;      // 5GB
    // 이 크기 초과면 멀티파트. 끊겨도 실패한 part 만 다시 올리면 됨
    private static final long MULTIPART_THRESHOLD = 64 * MB;
    private static final long MIN_PART_SIZE = 16 * MB;              // S3 최소 5MB
    private static final int TARGET_MAX_PARTS = 200;                // init 응답 크기 제한용

    // presigned URL 은 인스턴스 역할 임시 자격증명 만료 시 더 일찍 무효화될 수 있음 → 403 이면 /parts 로 재발급
    private static final Duration URL_TTL = Duration.ofHours(6);
    // iOS 백그라운드 업로드(셀룰러 대기 등)를 고려해 넉넉히
    private static final Duration SESSION_TTL = Duration.ofHours(48);

    private static final Map<String, String> EXT_BY_MIME = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/heic", ".heic",
            "image/heif", ".heif",
            "image/webp", ".webp",
            "image/gif", ".gif",
            "video/mp4", ".mp4",
            "video/quicktime", ".mov",
            "video/webm", ".webm"
    );

    // ==================== init ====================

    @Override
    public MediaUploadInitResponse initUpload(String travelId, MediaUploadInitRequest request, String userId) {
        findTravel(travelId);
        validateEditPermission(travelId, userId);

        String contentType = normalizeContentType(request.getContentType());
        if (!contentType.startsWith("image/") && !contentType.startsWith("video/")) {
            throw new CustomException(ErrorCode.INVALID_MEDIA_TYPE);
        }
        long fileSize = request.getFileSize();
        if (fileSize <= 0 || fileSize > MAX_FILE_SIZE) {
            throw new CustomException(ErrorCode.TRAVEL_MEDIA_TOO_LARGE);
        }
        // 실제 카운트는 complete 성공 시 증가. 여기선 이미 한도면 URL 발급 자체를 막는다
        if (rateLimitService.isDailyLimitReached(SCOPE_TRAVEL_UPLOAD, userId, MAX_DAILY_UPLOAD)) {
            throw new CustomException(ErrorCode.TRAVEL_UPLOAD_LIMIT_EXCEEDED);
        }

        String fileName = UUID.randomUUID() + resolveExtension(request.getFileName(), contentType);
        String key = s3Service.buildOriginKey(travelId, fileName);
        LocalDateTime now = LocalDateTime.now();

        TravelMediaUpload upload = TravelMediaUpload.builder()
                .travelId(travelId)
                .userId(userId)
                .status(MediaUploadStatus.PENDING)
                .s3Key(key)
                .fileName(fileName)
                .originalFileName(truncate(request.getFileName(), 255))
                .mimeType(contentType)
                .fileSize(fileSize)
                .width(request.getWidth())
                .height(request.getHeight())
                .duration(request.getDuration())
                .description(truncate(request.getDescription(), 1000))
                .takenAt(request.getTakenAt())
                .expiresAt(now.plus(SESSION_TTL))
                .build();

        if (fileSize > MULTIPART_THRESHOLD) {
            long partSize = computePartSize(fileSize);
            upload.setMethod(MediaUploadMethod.MULTIPART);
            upload.setPartSize(partSize);
            upload.setPartCount((int) ((fileSize + partSize - 1) / partSize));
            upload.setS3UploadId(s3Service.createMultipartUpload(key, contentType));
        } else {
            upload.setMethod(MediaUploadMethod.SINGLE);
            upload.setPartSize(fileSize);
            upload.setPartCount(1);
        }
        upload.setCreated(now);
        upload.setUpdated(now);
        upload.setCreatedUser(userId);
        upload.setUpdatedUser(userId);
        TravelMediaUpload saved = uploadRepo.save(upload);

        log.info("Media upload init: travel={} user={} upload={} method={} size={}",
                travelId, userId, saved.getId(), saved.getMethod(), fileSize);
        return buildUrlResponse(saved, null);
    }

    @Override
    public MediaUploadInitResponse refreshPartUrls(String travelId, String uploadId, List<Integer> partNumbers, String userId) {
        TravelMediaUpload upload = findOwnUpload(travelId, uploadId, userId);
        if (upload.getStatus() != MediaUploadStatus.PENDING) {
            throw new CustomException(ErrorCode.TRAVEL_MEDIA_UPLOAD_NOT_FOUND);
        }
        return buildUrlResponse(upload, partNumbers);
    }

    // ==================== complete ====================

    @Override
    public TravelMedia completeUpload(String travelId, String uploadId, String userId) {
        // 재시도(iOS 백그라운드 등)로 complete 가 두 번 와도 TravelMedia 가 하나만 생기도록 상태 전이를 원자적으로 잡는다
        TravelMediaUpload upload = claim(uploadId, travelId, userId, MediaUploadStatus.PENDING, MediaUploadStatus.COMPLETING);
        if (upload == null) {
            TravelMediaUpload existing = findOwnUpload(travelId, uploadId, userId);
            if (existing.getStatus() == MediaUploadStatus.COMPLETED && existing.getMediaId() != null) {
                return travelMediaRepo.findById(existing.getMediaId())
                        .orElseThrow(() -> new CustomException(ErrorCode.TRAVEL_MEDIA_NOT_FOUND));
            }
            if (existing.getStatus() == MediaUploadStatus.COMPLETING) {
                throw new CustomException(ErrorCode.TRAVEL_MEDIA_UPLOAD_IN_PROGRESS);
            }
            throw new CustomException(ErrorCode.TRAVEL_MEDIA_UPLOAD_NOT_FOUND);
        }

        try {
            if (upload.getMethod() == MediaUploadMethod.MULTIPART) {
                completeMultipart(upload);
            }

            HeadObjectResponse head = s3Service.headObject(upload.getS3Key());
            if (head == null) {
                throw new CustomException(ErrorCode.TRAVEL_MEDIA_UPLOAD_INCOMPLETE);
            }
            if (head.contentLength() == null || head.contentLength().longValue() != upload.getFileSize()) {
                // 서명된 크기와 다른 객체 — 남겨두지 않는다
                s3Service.deleteKey(upload.getS3Key());
                markStatus(upload, MediaUploadStatus.ABORTED);
                throw new CustomException(ErrorCode.TRAVEL_MEDIA_UPLOAD_MISMATCH);
            }

            TravelMedia media = saveMedia(upload);
            rateLimitService.incrementDaily(SCOPE_TRAVEL_UPLOAD, userId);

            upload.setMediaId(media.getId());
            markStatus(upload, MediaUploadStatus.COMPLETED);
            log.info("Media upload complete: travel={} upload={} media={}", travelId, uploadId, media.getId());
            return media;
        } catch (RuntimeException e) {
            // 아직 COMPLETING 이면 PENDING 으로 되돌려 클라이언트가 이어서 올리고 다시 complete 할 수 있게 함
            if (upload.getStatus() == MediaUploadStatus.COMPLETING) {
                markStatus(upload, MediaUploadStatus.PENDING);
            }
            throw e;
        }
    }

    private void completeMultipart(TravelMediaUpload upload) {
        List<CompletedPart> parts;
        try {
            parts = s3Service.listUploadedParts(upload.getS3Key(), upload.getS3UploadId());
        } catch (NoSuchUploadException e) {
            // 이전 complete 가 S3 완료 후 DB 저장 전에 끊긴 경우 — 객체가 있으면 headObject 단계에서 이어서 처리
            return;
        }
        if (parts.size() != upload.getPartCount()) {
            throw new CustomException(ErrorCode.TRAVEL_MEDIA_UPLOAD_INCOMPLETE,
                    parts.size() + "/" + upload.getPartCount() + " parts uploaded");
        }
        parts.sort((a, b) -> Integer.compare(a.partNumber(), b.partNumber()));
        try {
            s3Service.completeMultipartUpload(upload.getS3Key(), upload.getS3UploadId(), parts);
        } catch (Exception e) {
            log.error("S3 completeMultipartUpload failed upload={}: {}", upload.getId(), e.getMessage());
            throw new CustomException(ErrorCode.S3_UPLOAD_FAILED, e.getMessage());
        }
    }

    private TravelMedia saveMedia(TravelMediaUpload upload) {
        String fileUrl = s3Service.buildFileUrl(upload.getS3Key());
        LocalDateTime now = LocalDateTime.now();
        TravelMedia media = TravelMedia.builder()
                .travelId(upload.getTravelId())
                .uploadUserId(upload.getUserId())
                .fileName(upload.getFileName())
                .originalFileName(upload.getOriginalFileName())
                .fileUrl(fileUrl)
                .thumbnailUrl(s3Service.buildThumbnailUrl(fileUrl))
                .mimeType(upload.getMimeType())
                .fileSize(upload.getFileSize())
                .width(upload.getWidth())
                .height(upload.getHeight())
                .duration(upload.getDuration())
                .description(upload.getDescription())
                .takenAt(upload.getTakenAt())
                .build();
        media.setCreated(now);
        media.setUpdated(now);
        media.setCreatedUser(upload.getUserId());
        media.setUpdatedUser(upload.getUserId());
        return travelMediaRepo.save(media);
    }

    // ==================== abort / cleanup ====================

    @Override
    public void abortUpload(String travelId, String uploadId, String userId) {
        TravelMediaUpload upload = claim(uploadId, travelId, userId, MediaUploadStatus.PENDING, MediaUploadStatus.ABORTED);
        if (upload == null) {
            // 이미 완료·취소된 세션이면 조용히 무시 (취소 버튼 중복 클릭 등)
            findOwnUpload(travelId, uploadId, userId);
            return;
        }
        cleanupS3(upload);
    }

    @Override
    public int cleanupExpiredUploads() {
        LocalDateTime now = LocalDateTime.now();
        List<TravelMediaUpload> expired = uploadRepo.findTop100ByStatusInAndExpiresAtBefore(
                List.of(MediaUploadStatus.PENDING, MediaUploadStatus.COMPLETING), now);
        int cleaned = 0;
        for (TravelMediaUpload candidate : expired) {
            Query q = Query.query(Criteria.where("_id").is(candidate.getId())
                    .and("status").is(candidate.getStatus())
                    .and("expiresAt").lt(now));
            TravelMediaUpload claimed = mongoTemplate.findAndModify(q,
                    new Update().set("status", MediaUploadStatus.ABORTED).set("updated", now),
                    FindAndModifyOptions.options().returnNew(true), TravelMediaUpload.class);
            if (claimed == null) continue;
            cleanupS3(claimed);
            cleaned++;
        }
        return cleaned;
    }

    private void cleanupS3(TravelMediaUpload upload) {
        if (upload.getMethod() == MediaUploadMethod.MULTIPART && upload.getS3UploadId() != null) {
            s3Service.abortMultipartUpload(upload.getS3Key(), upload.getS3UploadId());
        }
        // SINGLE 은 PUT 이 끝났을 수 있고, MULTIPART 도 S3 complete 후 끊겼을 수 있음 → 객체 삭제(없으면 no-op).
        // 삭제 이벤트로 Lambda 가 썸네일도 함께 지운다
        s3Service.deleteKey(upload.getS3Key());
    }

    // ==================== helpers ====================

    private MediaUploadInitResponse buildUrlResponse(TravelMediaUpload upload, List<Integer> partNumbers) {
        List<MediaUploadPart> parts = new ArrayList<>();
        if (upload.getMethod() == MediaUploadMethod.SINGLE) {
            parts.add(MediaUploadPart.builder()
                    .partNumber(1)
                    .size(upload.getFileSize())
                    .url(s3Service.presignPut(upload.getS3Key(), upload.getMimeType(), upload.getFileSize(), URL_TTL))
                    .build());
        } else {
            List<Integer> targets = new ArrayList<>();
            if (partNumbers == null || partNumbers.isEmpty()) {
                for (int i = 1; i <= upload.getPartCount(); i++) targets.add(i);
            } else {
                partNumbers.stream()
                        .filter(n -> n != null && n >= 1 && n <= upload.getPartCount())
                        .distinct()
                        .forEach(targets::add);
            }
            for (int n : targets) {
                long size = partLength(upload, n);
                parts.add(MediaUploadPart.builder()
                        .partNumber(n)
                        .size(size)
                        .url(s3Service.presignUploadPart(upload.getS3Key(), upload.getS3UploadId(), n, size, URL_TTL))
                        .build());
            }
        }
        return MediaUploadInitResponse.builder()
                .uploadId(upload.getId())
                .method(upload.getMethod())
                .contentType(upload.getMimeType())
                .partSize(upload.getPartSize())
                .partCount(upload.getPartCount())
                .parts(parts)
                .urlExpiresAt(LocalDateTime.now().plus(URL_TTL))
                .sessionExpiresAt(upload.getExpiresAt())
                .build();
    }

    private long partLength(TravelMediaUpload upload, int partNumber) {
        long start = (long) (partNumber - 1) * upload.getPartSize();
        return Math.min(upload.getPartSize(), upload.getFileSize() - start);
    }

    /** part 수가 TARGET_MAX_PARTS 를 넘지 않도록 MB 단위로 올림 (최소 16MB) */
    private long computePartSize(long fileSize) {
        long perPart = (fileSize + TARGET_MAX_PARTS - 1) / TARGET_MAX_PARTS;
        long roundedUp = ((perPart + MB - 1) / MB) * MB;
        return Math.max(MIN_PART_SIZE, roundedUp);
    }

    private TravelMediaUpload claim(String uploadId, String travelId, String userId,
                                    MediaUploadStatus from, MediaUploadStatus to) {
        Query q = Query.query(Criteria.where("_id").is(uploadId)
                .and("travelId").is(travelId)
                .and("userId").is(userId)
                .and("status").is(from));
        return mongoTemplate.findAndModify(q,
                new Update().set("status", to).set("updated", LocalDateTime.now()),
                FindAndModifyOptions.options().returnNew(true), TravelMediaUpload.class);
    }

    private void markStatus(TravelMediaUpload upload, MediaUploadStatus status) {
        upload.setStatus(status);
        upload.setUpdated(LocalDateTime.now());
        uploadRepo.save(upload);
    }

    private TravelMediaUpload findOwnUpload(String travelId, String uploadId, String userId) {
        TravelMediaUpload upload = uploadRepo.findById(uploadId)
                .orElseThrow(() -> new CustomException(ErrorCode.TRAVEL_MEDIA_UPLOAD_NOT_FOUND));
        if (!upload.getTravelId().equals(travelId) || !upload.getUserId().equals(userId)) {
            throw new CustomException(ErrorCode.TRAVEL_MEDIA_UPLOAD_NOT_FOUND);
        }
        return upload;
    }

    private void findTravel(String travelId) {
        travelsRepo.findById(travelId)
                .orElseThrow(() -> new CustomException(ErrorCode.TRAVEL_NOT_FOUND));
    }

    // VIEWER는 편집 불가 (ADMIN, USER만 허용) — TravelServiceImpl 과 동일 규칙
    private void validateEditPermission(String travelId, String userId) {
        TravelUsers member = travelUsersRepo.findByTravelIdAndUserId(travelId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_TRAVEL_MEMBER));
        if (member.getRole() == TravelRole.VIEWER) {
            throw new CustomException(ErrorCode.UNAUTHORIZED_TRAVEL_ACCESS);
        }
    }

    private String normalizeContentType(String contentType) {
        String ct = contentType.trim().toLowerCase(Locale.ROOT);
        int semi = ct.indexOf(';');
        return semi >= 0 ? ct.substring(0, semi).trim() : ct;
    }

    /** 원본 파일명 확장자(영숫자 1~8자)를 쓰고, 없으면 MIME 으로 추정. Lambda 가 확장자로 영상 여부를 판단함 */
    private String resolveExtension(String fileName, String contentType) {
        if (fileName != null) {
            int dot = fileName.lastIndexOf('.');
            if (dot >= 0 && dot < fileName.length() - 1) {
                String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (ext.matches("[a-z0-9]{1,8}")) return "." + ext;
            }
        }
        return EXT_BY_MIME.getOrDefault(contentType, "");
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }
}
