package server.nadeliv.travel.service.serviceImpl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import server.nadeliv.common.service.RateLimitService;
import server.nadeliv.error.CustomException;
import server.nadeliv.error.ErrorCode;
import server.nadeliv.travel.model.dto.*;
import server.nadeliv.travel.model.embedded.TravelSchedule;
import server.nadeliv.travel.model.embedded.VisitedPlace;
import server.nadeliv.travel.model.entities.TravelMedia;
import server.nadeliv.travel.model.entities.TravelUsers;
import server.nadeliv.travel.model.entities.Travels;
import server.nadeliv.travel.model.enums.TravelRole;
import server.nadeliv.travel.model.enums.TravelVisibility;
import server.nadeliv.travel.model.mapper.TravelMapper;
import server.nadeliv.travel.repo.TravelChannelRepo;
import server.nadeliv.travel.repo.TravelMediaRepo;
import server.nadeliv.travel.repo.TravelUsersRepo;
import server.nadeliv.travel.repo.TravelsRepo;
import server.nadeliv.travel.service.S3Service;
import server.nadeliv.travel.service.TravelService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TravelServiceImpl implements TravelService {

    private final TravelsRepo travelsRepo;
    private final TravelUsersRepo travelUsersRepo;
    private final TravelMediaRepo travelMediaRepo;
    private final TravelMapper travelMapper;
    private final S3Service s3Service;
    private final TravelChannelRepo travelChannelRepo;
    private final RateLimitService rateLimitService;

    // 하루 업로드 가능한 파일 수 제한 (남용 방지)
    private static final String SCOPE_TRAVEL_UPLOAD = "travel:upload";
    private static final int MAX_DAILY_UPLOAD = 500;

    // 일정 항목 메모 최대 길이
    private static final int MAX_SCHEDULE_MEMO_LENGTH = 500;

    // ==================== Travel CRUD ====================

    @Override
    public TravelResponse createTravel(TravelCreateRequest request, String userId) {
        log.info("Creating travel project for user: {}", userId);

        if (request.getStartDate() != null && request.getEndDate() != null
                && request.getStartDate().isAfter(request.getEndDate())) {
            throw new CustomException(ErrorCode.INVALID_TRAVEL_DATE);
        }

        Travels travel = travelMapper.toEntity(request, userId);
        Travels savedTravel = travelsRepo.save(travel);

        // 생성자를 ADMIN으로 등록
        TravelUsers adminUser = TravelUsers.builder()
                .travelId(savedTravel.getId())
                .userId(userId)
                .role(TravelRole.ADMIN)
                .joinedAt(LocalDateTime.now())
                .build();
        adminUser.setCreated(LocalDateTime.now());
        adminUser.setUpdated(LocalDateTime.now());
        adminUser.setCreatedUser(userId);
        adminUser.setUpdatedUser(userId);
        travelUsersRepo.save(adminUser);

        List<TravelUsers> members = travelUsersRepo.findByTravelId(savedTravel.getId());
        log.info("Travel project created: {}", savedTravel.getId());
        return travelMapper.toResponse(savedTravel, members);
    }

    @Override
    public TravelResponse getTravel(String travelId, String userId) {
        Travels travel = findTravelById(travelId);
        validateTravelAccess(travel, userId);

        List<TravelUsers> members = travelUsersRepo.findByTravelId(travelId);
        return travelMapper.toResponse(travel, members);
    }

    @Override
    public TravelResponse updateTravel(String travelId, TravelUpdateRequest request, String userId) {
        log.info("Updating travel: {} by user: {}", travelId, userId);

        Travels travel = findTravelById(travelId);
        validateAdminRole(travelId, userId);

        if (request.getTitle() != null) travel.setTitle(request.getTitle());
        if (request.getDescription() != null) travel.setDescription(request.getDescription());
        if (request.getCoverImageUrl() != null) travel.setCoverImageUrl(request.getCoverImageUrl());
        if (request.getVisibility() != null) travel.setVisibility(request.getVisibility());
        if (request.getStatus() != null) travel.setStatus(request.getStatus());
        if (request.getStartDate() != null) travel.setStartDate(request.getStartDate());
        if (request.getEndDate() != null) travel.setEndDate(request.getEndDate());
        if (request.getDestination() != null) travel.setDestination(request.getDestination());
        if (request.getTags() != null) travel.setTags(request.getTags());
        if (request.getDashboardItems() != null) travel.setDashboardItems(request.getDashboardItems());

        if (travel.getStartDate() != null && travel.getEndDate() != null
                && travel.getStartDate().isAfter(travel.getEndDate())) {
            throw new CustomException(ErrorCode.INVALID_TRAVEL_DATE);
        }

        travel.setUpdated(LocalDateTime.now());
        travel.setUpdatedUser(userId);
        Travels updatedTravel = travelsRepo.save(travel);

        List<TravelUsers> members = travelUsersRepo.findByTravelId(travelId);
        return travelMapper.toResponse(updatedTravel, members);
    }

    @Override
    public void deleteTravel(String travelId, String userId) {
        log.info("Deleting travel: {} by user: {}", travelId, userId);

        findTravelById(travelId);
        validateAdminRole(travelId, userId);

        // 관련 데이터 삭제
        travelChannelRepo.deleteByTravelId(travelId);
        travelUsersRepo.deleteByTravelId(travelId);
        travelMediaRepo.deleteByTravelId(travelId);
        travelsRepo.deleteById(travelId);

        log.info("Travel project deleted: {}", travelId);
    }

    // ==================== Travel List ====================

    @Override
    public TravelListResponse getMyTravels(String userId, int page, int size) {
        List<TravelUsers> myMemberships = travelUsersRepo.findByUserId(userId);
        List<String> travelIds = myMemberships.stream().map(TravelUsers::getTravelId).toList();

        if (travelIds.isEmpty()) {
            return TravelListResponse.builder()
                    .travels(new ArrayList<>())
                    .pagination(TravelListResponse.PaginationInfo.builder()
                            .totalCount(0L)
                            .pageSize(size)
                            .currentPage(page)
                            .hasMore(false)
                            .build())
                    .build();
        }

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "created"));
        Page<Travels> travelPage = travelsRepo.findByIdIn(travelIds, pageRequest);

        List<TravelResponse> responses = travelPage.getContent().stream()
                .map(travel -> {
                    List<TravelUsers> members = travelUsersRepo.findByTravelId(travel.getId());
                    return travelMapper.toResponse(travel, members);
                })
                .toList();

        return TravelListResponse.builder()
                .travels(responses)
                .pagination(TravelListResponse.PaginationInfo.builder()
                        .totalCount(travelPage.getTotalElements())
                        .pageSize(size)
                        .currentPage(page)
                        .hasMore(travelPage.hasNext())
                        .build())
                .build();
    }

    @Override
    public TravelListResponse getPublicTravels(int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "created"));
        Page<Travels> travelPage = travelsRepo.findByVisibility(TravelVisibility.PUBLIC, pageRequest);

        List<TravelResponse> responses = travelPage.getContent().stream()
                .map(travel -> {
                    List<TravelUsers> members = travelUsersRepo.findByTravelId(travel.getId());
                    return travelMapper.toResponse(travel, members);
                })
                .toList();

        return TravelListResponse.builder()
                .travels(responses)
                .pagination(TravelListResponse.PaginationInfo.builder()
                        .totalCount(travelPage.getTotalElements())
                        .pageSize(size)
                        .currentPage(page)
                        .hasMore(travelPage.hasNext())
                        .build())
                .build();
    }

    // ==================== Member Management ====================

    @Override
    public TravelResponse addMember(String travelId, TravelMemberRequest request, String userId) {
        log.info("Adding member {} to travel {} by user {}", request.getUserId(), travelId, userId);

        Travels travel = findTravelById(travelId);
        validateAdminRole(travelId, userId);

        if (travelUsersRepo.existsByTravelIdAndUserId(travelId, request.getUserId())) {
            throw new CustomException(ErrorCode.ALREADY_TRAVEL_MEMBER);
        }

        TravelUsers newMember = TravelUsers.builder()
                .travelId(travelId)
                .userId(request.getUserId())
                .role(request.getRole() != null ? request.getRole() : TravelRole.USER)
                .nickname(request.getNickname())
                .joinedAt(LocalDateTime.now())
                .build();
        newMember.setCreated(LocalDateTime.now());
        newMember.setUpdated(LocalDateTime.now());
        newMember.setCreatedUser(userId);
        newMember.setUpdatedUser(userId);
        travelUsersRepo.save(newMember);

        List<TravelUsers> members = travelUsersRepo.findByTravelId(travelId);
        return travelMapper.toResponse(travel, members);
    }

    @Override
    public void removeMember(String travelId, String targetUserId, String userId) {
        log.info("Removing member {} from travel {} by user {}", targetUserId, travelId, userId);

        findTravelById(travelId);
        validateAdminRole(travelId, userId);

        if (!travelUsersRepo.existsByTravelIdAndUserId(travelId, targetUserId)) {
            throw new CustomException(ErrorCode.NOT_TRAVEL_MEMBER);
        }

        // 마지막 ADMIN 삭제 방지
        TravelUsers targetMember = travelUsersRepo.findByTravelIdAndUserId(travelId, targetUserId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_TRAVEL_MEMBER));

        if (targetMember.getRole() == TravelRole.ADMIN) {
            Long adminCount = travelUsersRepo.countByTravelIdAndRole(travelId, TravelRole.ADMIN);
            if (adminCount <= 1) {
                throw new CustomException(ErrorCode.CANNOT_REMOVE_LAST_ADMIN);
            }
        }

        travelUsersRepo.deleteByTravelIdAndUserId(travelId, targetUserId);
    }

    @Override
    public TravelResponse updateMemberRole(String travelId, String targetUserId, TravelRole role, String userId) {
        log.info("Updating role of member {} in travel {} to {} by user {}", targetUserId, travelId, role, userId);

        Travels travel = findTravelById(travelId);
        validateAdminRole(travelId, userId);

        TravelUsers targetMember = travelUsersRepo.findByTravelIdAndUserId(travelId, targetUserId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_TRAVEL_MEMBER));

        // ADMIN -> USER/VIEWER 변경 시 마지막 ADMIN 체크
        if (targetMember.getRole() == TravelRole.ADMIN && role != TravelRole.ADMIN) {
            Long adminCount = travelUsersRepo.countByTravelIdAndRole(travelId, TravelRole.ADMIN);
            if (adminCount <= 1) {
                throw new CustomException(ErrorCode.CANNOT_REMOVE_LAST_ADMIN);
            }
        }

        targetMember.setRole(role);
        targetMember.setUpdated(LocalDateTime.now());
        targetMember.setUpdatedUser(userId);
        travelUsersRepo.save(targetMember);

        List<TravelUsers> members = travelUsersRepo.findByTravelId(travelId);
        return travelMapper.toResponse(travel, members);
    }

    // ==================== Visited Regions (Korea Map) ====================

    @Override
    public TravelResponse updateVisitedRegions(String travelId, TravelRegionsRequest request, String userId) {
        Travels travel = findTravelById(travelId);
        validateEditPermission(travelId, userId);

        // 행정코드 형식(숫자 2~5자리)만 허용하고 중복 제거 후 전체 교체
        List<String> codes = request.getRegionCodes().stream()
                .filter(code -> code != null && code.matches("\\d{2,5}"))
                .distinct()
                .toList();

        travel.setVisitedRegionCodes(new ArrayList<>(codes));
        travel.setUpdated(LocalDateTime.now());
        travel.setUpdatedUser(userId);
        Travels updatedTravel = travelsRepo.save(travel);

        List<TravelUsers> members = travelUsersRepo.findByTravelId(travelId);
        return travelMapper.toResponse(updatedTravel, members);
    }

    @Override
    public TravelResponse updateVisitedPlaces(String travelId, TravelPlacesRequest request, String userId) {
        Travels travel = findTravelById(travelId);
        validateEditPermission(travelId, userId);

        // 이름·좌표 없는 항목 제외, 장소 ID 기준 중복 제거 후 전체 교체
        Set<String> seenIds = new HashSet<>();
        List<VisitedPlace> places = request.getPlaces().stream()
                .filter(p -> p != null && p.getName() != null && !p.getName().isBlank()
                        && p.getLat() != null && p.getLng() != null)
                .filter(p -> p.getId() == null || seenIds.add(p.getId()))
                .filter(p -> p.getRegionCode() == null || p.getRegionCode().matches("\\d{2,5}"))
                .toList();

        travel.setVisitedPlaces(new ArrayList<>(places));
        travel.setUpdated(LocalDateTime.now());
        travel.setUpdatedUser(userId);
        Travels updatedTravel = travelsRepo.save(travel);

        List<TravelUsers> members = travelUsersRepo.findByTravelId(travelId);
        return travelMapper.toResponse(updatedTravel, members);
    }

    // ==================== Schedule Management ====================

    @Override
    public TravelResponse addSchedule(String travelId, TravelScheduleRequest request, String userId) {
        Travels travel = findTravelById(travelId);
        validateEditPermission(travelId, userId);

        TravelSchedule schedule = TravelSchedule.builder()
                .id(UUID.randomUUID().toString())
                .dayNumber(request.getDayNumber())
                .date(request.getDate())
                .title(request.getTitle())
                .description(request.getDescription())
                .places(sanitizeSchedulePlaces(request.getPlaces()))
                .sortOrder(request.getSortOrder())
                .build();

        if (travel.getSchedules() == null) {
            travel.setSchedules(new ArrayList<>());
        }
        travel.getSchedules().add(schedule);
        travel.setUpdated(LocalDateTime.now());
        travel.setUpdatedUser(userId);
        Travels updatedTravel = travelsRepo.save(travel);

        List<TravelUsers> members = travelUsersRepo.findByTravelId(travelId);
        return travelMapper.toResponse(updatedTravel, members);
    }

    @Override
    public TravelResponse updateSchedule(String travelId, String scheduleId, TravelScheduleRequest request, String userId) {
        Travels travel = findTravelById(travelId);
        validateEditPermission(travelId, userId);

        TravelSchedule schedule = travel.getSchedules().stream()
                .filter(s -> s.getId().equals(scheduleId))
                .findFirst()
                .orElseThrow(() -> new CustomException(ErrorCode.TRAVEL_SCHEDULE_NOT_FOUND));

        if (request.getTitle() != null) schedule.setTitle(request.getTitle());
        if (request.getDayNumber() != null) schedule.setDayNumber(request.getDayNumber());
        if (request.getDate() != null) schedule.setDate(request.getDate());
        if (request.getDescription() != null) schedule.setDescription(request.getDescription());
        if (request.getPlaces() != null) schedule.setPlaces(sanitizeSchedulePlaces(request.getPlaces()));
        if (request.getSortOrder() != null) schedule.setSortOrder(request.getSortOrder());

        travel.setUpdated(LocalDateTime.now());
        travel.setUpdatedUser(userId);
        Travels updatedTravel = travelsRepo.save(travel);

        List<TravelUsers> members = travelUsersRepo.findByTravelId(travelId);
        return travelMapper.toResponse(updatedTravel, members);
    }

    @Override
    public TravelResponse deleteSchedule(String travelId, String scheduleId, String userId) {
        Travels travel = findTravelById(travelId);
        validateEditPermission(travelId, userId);

        boolean removed = travel.getSchedules().removeIf(s -> s.getId().equals(scheduleId));
        if (!removed) {
            throw new CustomException(ErrorCode.TRAVEL_SCHEDULE_NOT_FOUND);
        }

        travel.setUpdated(LocalDateTime.now());
        travel.setUpdatedUser(userId);
        Travels updatedTravel = travelsRepo.save(travel);

        List<TravelUsers> members = travelUsersRepo.findByTravelId(travelId);
        return travelMapper.toResponse(updatedTravel, members);
    }

    // ==================== Media Management ====================

    @Override
    public TravelMedia uploadMedia(String travelId, MultipartFile file, TravelMediaRequest request, String userId) {
        log.info("Uploading media to travel: {} by user: {}", travelId, userId);

        findTravelById(travelId);
        validateEditPermission(travelId, userId);

        // 남용 방지: 사용자당 하루 업로드 500개 제한. S3 업로드 전에 검사해 불필요한 부하를 막는다.
        if (rateLimitService.isDailyLimitReached(SCOPE_TRAVEL_UPLOAD, userId, MAX_DAILY_UPLOAD)) {
            throw new CustomException(ErrorCode.TRAVEL_UPLOAD_LIMIT_EXCEEDED);
        }

        String fileUrl = s3Service.uploadFile(file, travelId);
        String thumbnailUrl = s3Service.buildThumbnailUrl(fileUrl);

        TravelMedia media = TravelMedia.builder()
                .travelId(travelId)
                .uploadUserId(userId)
                .fileName(UUID.randomUUID().toString() + getExtension(file.getOriginalFilename()))
                .originalFileName(file.getOriginalFilename())
                .fileUrl(fileUrl)
                .thumbnailUrl(thumbnailUrl)
                .displayUrl(s3Service.buildDisplayUrl(fileUrl))
                .mimeType(file.getContentType())
                .fileSize(file.getSize())
                .width(request != null ? request.getWidth() : null)
                .height(request != null ? request.getHeight() : null)
                .duration(request != null ? request.getDuration() : null)
                .description(request != null ? request.getDescription() : null)
                .takenAt(request != null ? request.getTakenAt() : null)
                .build();
        media.setCreated(LocalDateTime.now());
        media.setUpdated(LocalDateTime.now());
        media.setCreatedUser(userId);
        media.setUpdatedUser(userId);

        TravelMedia saved = travelMediaRepo.save(media);

        // 업로드 성공 후에만 카운트 증가 (S3/DB 실패는 쿼터 소모 안 함)
        rateLimitService.incrementDaily(SCOPE_TRAVEL_UPLOAD, userId);

        return saved;
    }

    @Override
    public List<TravelMedia> getMediaList(String travelId, String userId, int page, int size) {
        return getMediaList(travelId, userId, page, size, null, null);
    }

    /**
     * 미디어 목록 (앨범 화면 무한 스크롤용).
     * @param sort created_desc(기본) | created_asc | taken_desc | taken_asc
     * @param type image | video | null(전체)
     */
    @Override
    public List<TravelMedia> getMediaList(String travelId, String userId, int page, int size, String sort, String type) {
        Travels travel = findTravelById(travelId);
        validateTravelAccess(travel, userId);

        // 한 번에 너무 많이 가져가지 않도록 상한
        int safeSize = Math.max(1, Math.min(size, 200));
        PageRequest pageRequest = PageRequest.of(page, safeSize, resolveMediaSort(sort));
        String mimePrefix = resolveMimePrefix(type);
        Page<TravelMedia> mediaPage = (mimePrefix == null)
                ? travelMediaRepo.findByTravelId(travelId, pageRequest)
                : travelMediaRepo.findByTravelIdAndMimeTypeStartingWith(travelId, mimePrefix, pageRequest);
        List<TravelMedia> mediaList = mediaPage.getContent();

        // thumbnailUrl·displayUrl 이 없거나 잘못된 경우 재계산 및 DB 업데이트
        // (displayUrl 은 3단계에서 추가 — 이전 레코드는 객체가 없을 수 있어 클라이언트가 원본으로 폴백한다)
        List<TravelMedia> toUpdate = new ArrayList<>();
        for (TravelMedia media : mediaList) {
            if (media.getFileUrl() != null) {
                boolean changed = false;
                String correctUrl = s3Service.buildThumbnailUrl(media.getFileUrl());
                if (correctUrl != null && !correctUrl.equals(media.getThumbnailUrl())) {
                    media.setThumbnailUrl(correctUrl);
                    changed = true;
                }
                String displayUrl = s3Service.buildDisplayUrl(media.getFileUrl());
                if (displayUrl != null && !displayUrl.equals(media.getDisplayUrl())) {
                    media.setDisplayUrl(displayUrl);
                    changed = true;
                }
                if (changed) toUpdate.add(media);
            }
        }
        if (!toUpdate.isEmpty()) {
            travelMediaRepo.saveAll(toUpdate);
        }

        return mediaList;
    }

    @Override
    public long countMedia(String travelId, String userId, String type) {
        Travels travel = findTravelById(travelId);
        validateTravelAccess(travel, userId);
        String mimePrefix = resolveMimePrefix(type);
        Long count = (mimePrefix == null)
                ? travelMediaRepo.countByTravelId(travelId)
                : travelMediaRepo.countByTravelIdAndMimeTypeStartingWith(travelId, mimePrefix);
        return count != null ? count : 0L;
    }

    private Sort resolveMediaSort(String sort) {
        if (sort == null) return Sort.by(Sort.Direction.DESC, "created");
        switch (sort) {
            case "created_asc":
                return Sort.by(Sort.Direction.ASC, "created");
            case "taken_desc":
                // takenAt 없는 문서(null)는 Mongo 정렬상 DESC 에서 뒤로 밀림 → created 로 2차 정렬
                return Sort.by(Sort.Order.desc("takenAt"), Sort.Order.desc("created"));
            case "taken_asc":
                return Sort.by(Sort.Order.asc("takenAt"), Sort.Order.asc("created"));
            case "created_desc":
            default:
                return Sort.by(Sort.Direction.DESC, "created");
        }
    }

    private String resolveMimePrefix(String type) {
        if (type == null || type.isBlank() || "all".equalsIgnoreCase(type)) return null;
        if ("image".equalsIgnoreCase(type)) return "image/";
        if ("video".equalsIgnoreCase(type)) return "video/";
        return null;
    }

    @Override
    public void deleteMedia(String travelId, String mediaId, String userId) {
        log.info("Deleting media: {} from travel: {} by user: {}", mediaId, travelId, userId);

        findTravelById(travelId);
        validateEditPermission(travelId, userId);

        TravelMedia media = travelMediaRepo.findById(mediaId)
                .orElseThrow(() -> new CustomException(ErrorCode.TRAVEL_MEDIA_NOT_FOUND));

        // 본인 업로드 또는 ADMIN만 삭제 가능
        if (!media.getUploadUserId().equals(userId)) {
            validateAdminRole(travelId, userId);
        }

        s3Service.deleteFile(media.getFileUrl());
        // 썸네일도 함께 삭제
        if (media.getThumbnailUrl() != null) {
            s3Service.deleteFile(media.getThumbnailUrl());
        }
        travelMediaRepo.deleteById(mediaId);
    }

    // ==================== Media Download ====================

    @Override
    public Resource downloadMedia(String travelId, String mediaId, String userId) {
        log.info("Downloading media: {} from travel: {} by user: {}", mediaId, travelId, userId);

        Travels travel = findTravelById(travelId);
        validateTravelAccess(travel, userId);

        TravelMedia media = travelMediaRepo.findById(mediaId)
                .orElseThrow(() -> new CustomException(ErrorCode.TRAVEL_MEDIA_NOT_FOUND));

        log.info("Download fileUrl: {}, thumbnailUrl: {}, fileSize: {}", media.getFileUrl(), media.getThumbnailUrl(), media.getFileSize());

        ResponseInputStream<GetObjectResponse> s3Object = s3Service.downloadFile(media.getFileUrl());
        return new InputStreamResource(s3Object);
    }

    @Override
    public TravelMedia getMediaInfo(String travelId, String mediaId, String userId) {
        Travels travel = findTravelById(travelId);
        validateTravelAccess(travel, userId);

        return travelMediaRepo.findById(mediaId)
                .orElseThrow(() -> new CustomException(ErrorCode.TRAVEL_MEDIA_NOT_FOUND));
    }

    // ==================== Helper Methods ====================

    // 일정 항목 정리: 이름 없는 항목 제외, 시각은 HH:mm 만 허용, 메모 길이 제한
    private List<TravelSchedule.SchedulePlace> sanitizeSchedulePlaces(List<TravelSchedule.SchedulePlace> places) {
        if (places == null) {
            return new ArrayList<>();
        }
        List<TravelSchedule.SchedulePlace> result = new ArrayList<>();
        for (TravelSchedule.SchedulePlace p : places) {
            if (p == null || p.getName() == null || p.getName().isBlank()) continue;
            if (p.getTime() != null && !p.getTime().matches("([01]\\d|2[0-3]):[0-5]\\d")) {
                p.setTime(null);
            }
            if (p.getMemo() != null && p.getMemo().length() > MAX_SCHEDULE_MEMO_LENGTH) {
                p.setMemo(p.getMemo().substring(0, MAX_SCHEDULE_MEMO_LENGTH));
            }
            result.add(p);
        }
        return result;
    }

    private Travels findTravelById(String travelId) {
        return travelsRepo.findById(travelId)
                .orElseThrow(() -> new CustomException(ErrorCode.TRAVEL_NOT_FOUND));
    }

    private void validateTravelAccess(Travels travel, String userId) {
        if (travel.getVisibility() == TravelVisibility.PUBLIC) {
            return;
        }
        if (!travelUsersRepo.existsByTravelIdAndUserId(travel.getId(), userId)) {
            throw new CustomException(ErrorCode.UNAUTHORIZED_TRAVEL_ACCESS);
        }
    }

    private void validateMembership(String travelId, String userId) {
        if (!travelUsersRepo.existsByTravelIdAndUserId(travelId, userId)) {
            throw new CustomException(ErrorCode.NOT_TRAVEL_MEMBER);
        }
    }

    private void validateAdminRole(String travelId, String userId) {
        TravelUsers member = travelUsersRepo.findByTravelIdAndUserId(travelId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_TRAVEL_MEMBER));

        if (member.getRole() != TravelRole.ADMIN) {
            throw new CustomException(ErrorCode.UNAUTHORIZED_MEMBER_MANAGE);
        }
    }

    // VIEWER는 편집 불가 (ADMIN, USER만 허용)
    private void validateEditPermission(String travelId, String userId) {
        TravelUsers member = travelUsersRepo.findByTravelIdAndUserId(travelId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_TRAVEL_MEMBER));

        if (member.getRole() == TravelRole.VIEWER) {
            throw new CustomException(ErrorCode.UNAUTHORIZED_TRAVEL_ACCESS);
        }
    }

    private String getExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf("."));
    }
}
