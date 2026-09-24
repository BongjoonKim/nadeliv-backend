package server.nadeliv.inquiry.service.serviceImpl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import server.nadeliv.error.CustomException;
import server.nadeliv.error.ErrorCode;
import server.nadeliv.inquiry.dto.request.InboundMailRequest;
import server.nadeliv.inquiry.dto.response.InquiryListResponse;
import server.nadeliv.inquiry.dto.response.InquiryResponse;
import server.nadeliv.inquiry.model.Inquiry;
import server.nadeliv.inquiry.repo.InquiryRepo;
import server.nadeliv.inquiry.service.InquiryService;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class InquiryServiceImpl implements InquiryService {

    // 본문 저장 상한 — 비정상적으로 큰 메일이 문서 크기(16MB) 를 위협하지 않도록
    private static final int MAX_BODY_LEN = 200_000;

    private final InquiryRepo inquiryRepo;
    private final MongoTemplate mongoTemplate;

    @Override
    public boolean ingest(InboundMailRequest request) {
        if (request.getMessageId() == null || request.getMessageId().isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }
        if (inquiryRepo.existsByMessageId(request.getMessageId())) {
            log.info("inquiry duplicate messageId={} — skip", request.getMessageId());
            return false;
        }

        LocalDateTime now = LocalDateTime.now();
        Inquiry inquiry = Inquiry.builder()
                .messageId(request.getMessageId())
                .recipient(lower(request.getRecipient()))
                .fromName(request.getFromName())
                .fromEmail(lower(request.getFromEmail()))
                .subject(request.getSubject())
                .textBody(truncate(request.getTextBody()))
                .htmlBody(truncate(request.getHtmlBody()))
                .receivedAt(parseReceivedAt(request.getReceivedAt(), now))
                .s3Key(request.getS3Key())
                .attachments(toAttachments(request.getAttachments()))
                .isRead(false)
                .isDeleted(false)
                .build();
        inquiry.setCreated(now);
        inquiry.setUpdated(now);
        inquiry.setCreatedUser("ses-inbound");

        try {
            inquiryRepo.save(inquiry);
        } catch (DuplicateKeyException e) {
            // 동시 재시도 경합 — unique index 가 최종 방어선
            log.info("inquiry duplicate (race) messageId={}", request.getMessageId());
            return false;
        }
        log.info("inquiry saved messageId={} from={}", request.getMessageId(), inquiry.getFromEmail());
        return true;
    }

    @Override
    public InquiryListResponse getInquiries(int page, int size, String status) {
        Query query = new Query(Criteria.where("isDeleted").is(false));
        if ("unread".equalsIgnoreCase(status)) {
            query.addCriteria(Criteria.where("isRead").is(false));
        } else if ("read".equalsIgnoreCase(status)) {
            query.addCriteria(Criteria.where("isRead").is(true));
        }

        long total = mongoTemplate.count(query, Inquiry.class);
        int totalPages = (int) Math.ceil((double) total / size);

        query.with(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "receivedAt")));
        List<Inquiry> inquiries = mongoTemplate.find(query, Inquiry.class);

        return InquiryListResponse.builder()
                .inquiries(inquiries.stream().map(InquiryResponse::summary).collect(Collectors.toList()))
                .totalCount(total)
                .totalPages(totalPages)
                .currentPage(page)
                .hasNext(page + 1 < totalPages)
                .totalAll(inquiryRepo.countByIsDeletedFalse())
                .unreadCount(inquiryRepo.countByIsDeletedFalseAndIsReadFalse())
                .build();
    }

    @Override
    public InquiryResponse getInquiry(String id) {
        return InquiryResponse.detail(findActive(id));
    }

    @Override
    public InquiryResponse updateRead(String id, boolean read, String adminUserId) {
        Inquiry inquiry = findActive(id);
        inquiry.setRead(read);
        inquiry.setUpdated(LocalDateTime.now());
        inquiry.setUpdatedUser(adminUserId);
        return InquiryResponse.detail(inquiryRepo.save(inquiry));
    }

    @Override
    public void delete(String id, String adminUserId) {
        Inquiry inquiry = findActive(id);
        inquiry.setDeleted(true);
        inquiry.setUpdated(LocalDateTime.now());
        inquiry.setUpdatedUser(adminUserId);
        inquiryRepo.save(inquiry);
    }

    // ---------- helpers ----------

    private Inquiry findActive(String id) {
        return inquiryRepo.findById(id)
                .filter(i -> !i.isDeleted())
                .orElseThrow(() -> new CustomException(ErrorCode.INQUIRY_NOT_FOUND));
    }

    private static LocalDateTime parseReceivedAt(String iso, LocalDateTime fallback) {
        if (iso == null || iso.isBlank()) return fallback;
        try {
            return LocalDateTime.ofInstant(Instant.parse(iso), ZoneId.systemDefault());
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }

    private static List<Inquiry.Attachment> toAttachments(List<InboundMailRequest.AttachmentMeta> metas) {
        if (metas == null) return List.of();
        return metas.stream()
                .map(m -> Inquiry.Attachment.builder()
                        .filename(m.getFilename())
                        .contentType(m.getContentType())
                        .size(m.getSize())
                        .build())
                .collect(Collectors.toList());
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > MAX_BODY_LEN ? s.substring(0, MAX_BODY_LEN) : s;
    }

    private static String lower(String s) {
        return s == null ? null : s.trim().toLowerCase();
    }
}
