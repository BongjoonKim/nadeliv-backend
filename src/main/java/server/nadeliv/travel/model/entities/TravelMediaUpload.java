package server.nadeliv.travel.model.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import server.nadeliv.common.dto.CommonDTO;
import server.nadeliv.travel.model.enums.MediaUploadMethod;
import server.nadeliv.travel.model.enums.MediaUploadStatus;

import java.time.LocalDateTime;

/**
 * presigned 직접 업로드 세션. init 때 생성 → complete 때 TravelMedia 로 등록.
 * 만료(expiresAt)된 PENDING 세션은 TravelMediaUploadCleanupScheduler 가 S3 정리 후 ABORTED 처리.
 */
@Document(collection = "travel_media_uploads")
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TravelMediaUpload extends CommonDTO {
    @Id
    private String id;

    @Indexed
    private String travelId;
    private String userId;

    private MediaUploadMethod method;
    private MediaUploadStatus status;

    private String s3Key;
    private String s3UploadId;   // MULTIPART 일 때만
    private Long partSize;
    private Integer partCount;

    private String fileName;
    private String originalFileName;
    private String mimeType;
    private Long fileSize;

    // 클라이언트가 추출한 메타데이터 (EXIF / video metadata / PHAsset)
    private Integer width;
    private Integer height;
    private Integer duration;
    private String description;
    private LocalDateTime takenAt;

    @Indexed
    private LocalDateTime expiresAt;
    private String mediaId;      // COMPLETED 후 생성된 TravelMedia id
}
