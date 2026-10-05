package server.nadeliv.travel.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 단일 파일 다운로드 — 브라우저·앱이 S3 에서 바로 받는 presigned GET URL */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class MediaDownloadUrlResponse {
    private String url;
    private String fileName;
    private LocalDateTime expiresAt;
}
