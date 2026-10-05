package server.nadeliv.travel.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import server.nadeliv.travel.model.dto.*;
import server.nadeliv.travel.model.entities.TravelMedia;
import server.nadeliv.travel.model.enums.TravelRole;
import server.nadeliv.travel.service.TravelMediaDownloadService;
import server.nadeliv.travel.service.TravelMediaUploadService;
import server.nadeliv.travel.service.TravelService;
import server.nadeliv.users.dto.CustomUserDetails;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/travels")
public class TravelController {

    private final TravelService travelService;
    private final TravelMediaUploadService travelMediaUploadService;
    private final TravelMediaDownloadService travelMediaDownloadService;

    // ==================== Travel CRUD ====================

    @PostMapping
    public ResponseEntity<TravelResponse> createTravel(
            @Valid @RequestBody TravelCreateRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        TravelResponse response = travelService.createTravel(request, userDetails.getUsername());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{travelId}")
    public ResponseEntity<TravelResponse> getTravel(
            @PathVariable String travelId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        TravelResponse response = travelService.getTravel(travelId, userDetails.getUsername());
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{travelId}")
    public ResponseEntity<TravelResponse> updateTravel(
            @PathVariable String travelId,
            @Valid @RequestBody TravelUpdateRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        TravelResponse response = travelService.updateTravel(travelId, request, userDetails.getUsername());
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{travelId}")
    public ResponseEntity<Void> deleteTravel(
            @PathVariable String travelId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        travelService.deleteTravel(travelId, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    // ==================== Travel List ====================

    @GetMapping("/my")
    public ResponseEntity<TravelListResponse> getMyTravels(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        TravelListResponse response = travelService.getMyTravels(userDetails.getUsername(), page, size);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/public")
    public ResponseEntity<TravelListResponse> getPublicTravels(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        TravelListResponse response = travelService.getPublicTravels(page, size);
        return ResponseEntity.ok(response);
    }

    // ==================== Member Management ====================

    @PostMapping("/{travelId}/members")
    public ResponseEntity<TravelResponse> addMember(
            @PathVariable String travelId,
            @Valid @RequestBody TravelMemberRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        TravelResponse response = travelService.addMember(travelId, request, userDetails.getUsername());
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{travelId}/members/{userId}")
    public ResponseEntity<Void> removeMember(
            @PathVariable String travelId,
            @PathVariable String userId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        travelService.removeMember(travelId, userId, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{travelId}/members/{userId}/role")
    public ResponseEntity<TravelResponse> updateMemberRole(
            @PathVariable String travelId,
            @PathVariable String userId,
            @RequestParam TravelRole role,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        TravelResponse response = travelService.updateMemberRole(travelId, userId, role, userDetails.getUsername());
        return ResponseEntity.ok(response);
    }

    // ==================== Visited Regions (Korea Map) ====================

    @PutMapping("/{travelId}/regions")
    public ResponseEntity<TravelResponse> updateVisitedRegions(
            @PathVariable String travelId,
            @Valid @RequestBody TravelRegionsRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        TravelResponse response = travelService.updateVisitedRegions(travelId, request, userDetails.getUsername());
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{travelId}/places")
    public ResponseEntity<TravelResponse> updateVisitedPlaces(
            @PathVariable String travelId,
            @Valid @RequestBody TravelPlacesRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        TravelResponse response = travelService.updateVisitedPlaces(travelId, request, userDetails.getUsername());
        return ResponseEntity.ok(response);
    }

    // ==================== Schedule Management ====================

    @PostMapping("/{travelId}/schedules")
    public ResponseEntity<TravelResponse> addSchedule(
            @PathVariable String travelId,
            @Valid @RequestBody TravelScheduleRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        TravelResponse response = travelService.addSchedule(travelId, request, userDetails.getUsername());
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{travelId}/schedules/{scheduleId}")
    public ResponseEntity<TravelResponse> updateSchedule(
            @PathVariable String travelId,
            @PathVariable String scheduleId,
            @Valid @RequestBody TravelScheduleRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        TravelResponse response = travelService.updateSchedule(travelId, scheduleId, request, userDetails.getUsername());
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{travelId}/schedules/{scheduleId}")
    public ResponseEntity<Void> deleteSchedule(
            @PathVariable String travelId,
            @PathVariable String scheduleId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        travelService.deleteSchedule(travelId, scheduleId, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    // ==================== Media Management ====================

    @PostMapping("/{travelId}/media")
    public ResponseEntity<TravelMedia> uploadMedia(
            @PathVariable String travelId,
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "request", required = false) TravelMediaRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        TravelMedia media = travelService.uploadMedia(travelId, file, request, userDetails.getUsername());
        return ResponseEntity.ok(media);
    }

    // ==================== Media Direct Upload (presigned, 웹·iOS 공용) ====================

    /** 업로드 시작 — S3 에 직접 PUT 할 URL 발급. 64MB 초과는 멀티파트 */
    @PostMapping("/{travelId}/media/uploads")
    public ResponseEntity<MediaUploadInitResponse> initMediaUpload(
            @PathVariable String travelId,
            @Valid @RequestBody MediaUploadInitRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(
                travelMediaUploadService.initUpload(travelId, request, userDetails.getUsername()));
    }

    /** 만료된 part URL 재발급 (partNumbers 비우면 전체) */
    @PostMapping("/{travelId}/media/uploads/{uploadId}/parts")
    public ResponseEntity<MediaUploadInitResponse> refreshMediaUploadParts(
            @PathVariable String travelId,
            @PathVariable String uploadId,
            @RequestBody(required = false) MediaUploadPartsRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(travelMediaUploadService.refreshPartUrls(
                travelId, uploadId, request != null ? request.getPartNumbers() : null, userDetails.getUsername()));
    }

    /** 업로드 완료 — 서버가 S3 를 확인한 뒤 TravelMedia 등록. 재호출해도 같은 미디어 반환 */
    @PostMapping("/{travelId}/media/uploads/{uploadId}/complete")
    public ResponseEntity<TravelMedia> completeMediaUpload(
            @PathVariable String travelId,
            @PathVariable String uploadId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(
                travelMediaUploadService.completeUpload(travelId, uploadId, userDetails.getUsername()));
    }

    @DeleteMapping("/{travelId}/media/uploads/{uploadId}")
    public ResponseEntity<Void> abortMediaUpload(
            @PathVariable String travelId,
            @PathVariable String uploadId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        travelMediaUploadService.abortUpload(travelId, uploadId, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    /**
     * 미디어 목록. 앨범 화면 무한 스크롤용으로 정렬·타입 필터를 받는다.
     * sort: created_desc(기본) | created_asc | taken_desc | taken_asc
     * type: all(기본) | image | video
     */
    @GetMapping("/{travelId}/media")
    public ResponseEntity<List<TravelMedia>> getMediaList(
            @PathVariable String travelId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String type,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        List<TravelMedia> mediaList = travelService.getMediaList(
                travelId, userDetails.getUsername(), page, size, sort, type);
        return ResponseEntity.ok(mediaList);
    }

    /** 미디어 개수 (대시보드 Album 박스·앨범 헤더용). type: all | image | video */
    @GetMapping("/{travelId}/media/count")
    public ResponseEntity<Map<String, Long>> getMediaCount(
            @PathVariable String travelId,
            @RequestParam(required = false) String type,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        long count = travelService.countMedia(travelId, userDetails.getUsername(), type);
        return ResponseEntity.ok(Map.of("count", count));
    }

    @DeleteMapping("/{travelId}/media/{mediaId}")
    public ResponseEntity<Void> deleteMedia(
            @PathVariable String travelId,
            @PathVariable String mediaId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        travelService.deleteMedia(travelId, mediaId, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    // ==================== Media Download (웹·iOS 공용) ====================

    /** 단일 파일 — S3 presigned GET URL. 클라이언트가 이 URL 로 이동하면 원본 파일명으로 저장된다 */
    @GetMapping("/{travelId}/media/{mediaId}/download-url")
    public ResponseEntity<MediaDownloadUrlResponse> getMediaDownloadUrl(
            @PathVariable String travelId,
            @PathVariable String mediaId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(
                travelMediaDownloadService.createDownloadUrl(travelId, mediaId, userDetails.getUsername()));
    }

    /** 일괄 다운로드 티켓 발급 — 응답의 path 로 GET 하면 ZIP 이 스트리밍된다 */
    @PostMapping("/{travelId}/media/downloads")
    public ResponseEntity<MediaDownloadTicketResponse> createMediaDownloadTicket(
            @PathVariable String travelId,
            @Valid @RequestBody MediaDownloadRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(
                travelMediaDownloadService.createTicket(travelId, request.getMediaIds(), userDetails.getUsername()));
    }

    /**
     * 티켓으로 ZIP 스트리밍 (비인증 — 'ps' 경로, 티켓 자체가 256bit 난수).
     * 브라우저는 <a href> 로 열어 디스크에 바로 받는다. 응답은 chunked 로 흘러간다.
     */
    @GetMapping("/ps/downloads/{ticket}")
    public void streamMediaDownload(
            @PathVariable String ticket,
            HttpServletResponse response) {
        travelMediaDownloadService.streamZip(ticket, response);
    }

    /** 구버전 단일 다운로드 (EC2 경유). 프론트 전환기 호환용 — download-url 로 대체됨 */
    @GetMapping("/{travelId}/media/{mediaId}/download")
    public ResponseEntity<Resource> downloadMedia(
            @PathVariable String travelId,
            @PathVariable String mediaId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        TravelMedia media = travelService.getMediaInfo(travelId, mediaId, userDetails.getUsername());
        Resource resource = travelService.downloadMedia(travelId, mediaId, userDetails.getUsername());

        String encodedFileName = URLEncoder.encode(
                media.getOriginalFileName() != null ? media.getOriginalFileName() : media.getFileName(),
                StandardCharsets.UTF_8
        ).replace("+", "%20");

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(media.getMimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encodedFileName)
                .body(resource);
    }

    /** 구버전 일괄 다운로드. 메모리 ZIP 대신 스트리밍으로 바뀌었고, 프론트 전환기 호환용으로 유지 */
    @PostMapping("/{travelId}/media/download")
    public void downloadMediaBatch(
            @PathVariable String travelId,
            @Valid @RequestBody MediaDownloadRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            HttpServletResponse response) {
        travelMediaDownloadService.streamZip(travelId, request.getMediaIds(), userDetails.getUsername(), response);
    }
}
