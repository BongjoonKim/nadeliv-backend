package server.nadeliv.travel.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import server.nadeliv.travel.model.enums.MediaUploadMethod;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SINGLE   : parts 1개 — url 에 PUT, Content-Type 헤더 = contentType 필수
 * MULTIPART: parts N개 — 각 url 에 해당 바이트 범위를 PUT (Content-Type 헤더 없이)
 * part URL 이 만료(403)되면 POST .../uploads/{uploadId}/parts 로 재발급.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class MediaUploadInitResponse {
    private String uploadId;
    private MediaUploadMethod method;
    private String contentType;
    private Long partSize;
    private Integer partCount;
    private List<MediaUploadPart> parts;
    /** URL 서명 만료 시각 (서버 시각 기준) */
    private LocalDateTime urlExpiresAt;
    /** 업로드 세션 자체의 만료 — 이 시각 전에 complete 해야 함 */
    private LocalDateTime sessionExpiresAt;
}
