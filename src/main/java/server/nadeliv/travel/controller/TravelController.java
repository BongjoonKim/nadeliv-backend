package server.nadeliv.travel.controller;

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

    // ==================== Media Download ====================

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

    @PostMapping("/{travelId}/media/download")
    public ResponseEntity<Resource> downloadMediaBatch(
            @PathVariable String travelId,
            @Valid @RequestBody MediaDownloadRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Resource resource = travelService.downloadMediaBatch(
                travelId, request.getMediaIds(), userDetails.getUsername());

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"travel-media.zip\"")
                .body(resource);
    }
}
