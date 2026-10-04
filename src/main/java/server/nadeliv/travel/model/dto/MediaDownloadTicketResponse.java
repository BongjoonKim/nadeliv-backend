package server.nadeliv.travel.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 일괄(ZIP) 다운로드 티켓.
 * 클라이언트는 url(GET, 비인증) 로 이동하면 ZIP 이 스트리밍으로 내려온다 — 브라우저가 메모리에 담지 않고 디스크로 받는다.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class MediaDownloadTicketResponse {
    private String ticket;
    /** 상대 경로 (api/v1/travels/ps/downloads/{ticket}). 클라이언트가 자기 API base 에 붙인다 */
    private String path;
    private String fileName;
    private Integer fileCount;
    private Long totalBytes;
    private LocalDateTime expiresAt;
}
