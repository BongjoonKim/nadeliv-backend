package server.nadeliv.inquiry.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import server.nadeliv.inquiry.model.Inquiry;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 관리자 문의함 응답. 목록에서는 본문(textBody/htmlBody) 을 비우고 preview 만 내려준다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InquiryResponse {
    private String id;
    private String recipient;
    private String fromName;
    private String fromEmail;
    private String subject;
    /** 목록용 본문 앞부분 (텍스트 기준 최대 140자) */
    private String preview;
    private String textBody;
    private String htmlBody;
    private LocalDateTime receivedAt;
    private List<Inquiry.Attachment> attachments;
    private Integer attachmentCount;
    private Boolean isRead;
    private LocalDateTime created;

    private static final int PREVIEW_LEN = 140;

    public static InquiryResponse summary(Inquiry inquiry) {
        return base(inquiry).build();
    }

    public static InquiryResponse detail(Inquiry inquiry) {
        return base(inquiry)
                .textBody(inquiry.getTextBody())
                .htmlBody(inquiry.getHtmlBody())
                .attachments(inquiry.getAttachments())
                .build();
    }

    private static InquiryResponseBuilder base(Inquiry inquiry) {
        String text = inquiry.getTextBody() == null ? "" : inquiry.getTextBody().strip();
        String preview = text.length() > PREVIEW_LEN ? text.substring(0, PREVIEW_LEN) + "…" : text;
        int attachmentCount = inquiry.getAttachments() == null ? 0 : inquiry.getAttachments().size();
        return InquiryResponse.builder()
                .id(inquiry.getId())
                .recipient(inquiry.getRecipient())
                .fromName(inquiry.getFromName())
                .fromEmail(inquiry.getFromEmail())
                .subject(inquiry.getSubject())
                .preview(preview)
                .receivedAt(inquiry.getReceivedAt())
                .attachmentCount(attachmentCount)
                .isRead(inquiry.isRead())
                .created(inquiry.getCreated());
    }
}
