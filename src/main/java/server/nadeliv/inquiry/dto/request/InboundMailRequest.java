package server.nadeliv.inquiry.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Lambda(nadeliv-mail-forward) 가 보내는 파싱된 수신 메일.
 * 필드명은 Lambda 의 build_payload() 와 1:1 로 맞춘다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InboundMailRequest {
    private String messageId;
    private String recipient;
    private String fromName;
    private String fromEmail;
    private String subject;
    private String textBody;
    private String htmlBody;
    /** ISO-8601 (예: 2026-09-24T03:12:45.123Z) */
    private String receivedAt;
    private String s3Key;
    private List<AttachmentMeta> attachments;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AttachmentMeta {
        private String filename;
        private String contentType;
        private Long size;
    }
}
