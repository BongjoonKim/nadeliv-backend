package server.nadeliv.travel.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class MediaUploadPart {
    private Integer partNumber;
    /** 이 part 의 바이트 수 (서명에 포함됨 — 정확히 이 크기로 PUT 해야 함) */
    private Long size;
    private String url;
}
