package server.nadeliv.travel.service;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;
import server.nadeliv.travel.model.dto.*;
import server.nadeliv.travel.model.entities.TravelMedia;
import server.nadeliv.travel.model.enums.TravelRole;

import java.util.List;

public interface TravelService {

    // Travel CRUD
    TravelResponse createTravel(TravelCreateRequest request, String userId);
    TravelResponse getTravel(String travelId, String userId);
    TravelResponse updateTravel(String travelId, TravelUpdateRequest request, String userId);
    void deleteTravel(String travelId, String userId);

    // Travel List
    TravelListResponse getMyTravels(String userId, int page, int size);
    TravelListResponse getPublicTravels(int page, int size);

    // Member Management
    TravelResponse addMember(String travelId, TravelMemberRequest request, String userId);
    void removeMember(String travelId, String targetUserId, String userId);
    TravelResponse updateMemberRole(String travelId, String targetUserId, TravelRole role, String userId);

    // Visited Regions (Korea Map)
    TravelResponse updateVisitedRegions(String travelId, TravelRegionsRequest request, String userId);

    TravelResponse updateVisitedPlaces(String travelId, TravelPlacesRequest request, String userId);

    // Schedule Management
    TravelResponse addSchedule(String travelId, TravelScheduleRequest request, String userId);
    TravelResponse updateSchedule(String travelId, String scheduleId, TravelScheduleRequest request, String userId);
    TravelResponse deleteSchedule(String travelId, String scheduleId, String userId);

    // Media Management
    TravelMedia uploadMedia(String travelId, MultipartFile file, TravelMediaRequest request, String userId);
    List<TravelMedia> getMediaList(String travelId, String userId, int page, int size);
    List<TravelMedia> getMediaList(String travelId, String userId, int page, int size, String sort, String type);
    long countMedia(String travelId, String userId, String type);
    void deleteMedia(String travelId, String mediaId, String userId);

    // Media Download
    Resource downloadMedia(String travelId, String mediaId, String userId);
    TravelMedia getMediaInfo(String travelId, String mediaId, String userId);
}
