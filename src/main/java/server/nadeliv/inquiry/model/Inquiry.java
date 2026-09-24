package server.nadeliv.inquiry.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import server.nadeliv.common.dto.CommonDTO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 사이트 문의 메일 (contact@nadeliv.com 으로 수신된 메일).
 *
 * 흐름: SES 수신 → S3 원본 저장 → Lambda 가 파싱 → 백엔드 웹훅 → 이 컬렉션.
 * 원본(.eml)은 S3 에 30일 보관되고 여기엔 파싱된 본문만 저장한다.
 */
@Document(collection = "inquiries")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Inquiry extends CommonDTO {

    @Id
    private String id;

    /** SES 메시지 ID — 웹훅 재시도 시 중복 저장 방지 키 */
    @Indexed(unique = true)
    private String messageId;

    /** 수신 주소 (예: contact@nadeliv.com) */
    private String recipient;

    private String fromName;
    @Indexed
    private String fromEmail;

    private String subject;
    private String textBody;
    private String htmlBody;

    /** SES 가 메일을 받은 시각 */
    @Indexed
    private LocalDateTime receivedAt;

    /** S3 원본 키 (버킷 nadeliv-mail-inbox) */
    private String s3Key;

    private List<Attachment> attachments;

    // primitive boolean → Lombok getter isRead()/isDeleted(), 파생 쿼리는 IsReadFalse/IsDeletedFalse (BlogsRepo 와 동일)
    private boolean isRead;
    private boolean isDeleted;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Attachment {
        private String filename;
        private String contentType;
        private Long size;
    }
}
