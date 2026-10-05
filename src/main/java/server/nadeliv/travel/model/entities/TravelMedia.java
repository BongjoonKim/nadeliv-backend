package server.nadeliv.travel.model.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import server.nadeliv.common.dto.CommonDTO;

import java.time.LocalDateTime;

@Document(collection = "travel_media")
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TravelMedia extends CommonDTO {
    @Id
    private String id;

    @Indexed
    private String travelId;

    private String uploadUserId;
    private String fileName;
    private String originalFileName;
    private String fileUrl;
    private String thumbnailUrl;
    /** 중간 크기(2048px) JPEG — 라이트박스·뷰어용. 영상은 null. Lambda 가 /display/ 에 생성 */
    private String displayUrl;
    private String mimeType;
    private Long fileSize;
    private Integer width;
    private Integer height;
    private Integer duration;
    private String description;
    private LocalDateTime takenAt;
}
