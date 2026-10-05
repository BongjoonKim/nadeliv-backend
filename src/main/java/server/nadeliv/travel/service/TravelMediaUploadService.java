package server.nadeliv.travel.service;

import server.nadeliv.travel.model.dto.MediaUploadInitRequest;
import server.nadeliv.travel.model.dto.MediaUploadInitResponse;
import server.nadeliv.travel.model.entities.TravelMedia;

import java.util.List;

/**
 * Travel 미디어 presigned 직접 업로드 (웹·iOS 공용).
 * init → (클라이언트가 S3 에 PUT) → complete 순서.
 */
public interface TravelMediaUploadService {
    MediaUploadInitResponse initUpload(String travelId, MediaUploadInitRequest request, String userId);
    MediaUploadInitResponse refreshPartUrls(String travelId, String uploadId, List<Integer> partNumbers, String userId);
    TravelMedia completeUpload(String travelId, String uploadId, String userId);
    void abortUpload(String travelId, String uploadId, String userId);

    /** 만료된 세션 정리 (스케줄러용). 처리 건수 반환 */
    int cleanupExpiredUploads();
}
