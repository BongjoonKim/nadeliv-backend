package server.nadeliv.inquiry.service;

import server.nadeliv.inquiry.dto.request.InboundMailRequest;
import server.nadeliv.inquiry.dto.response.InquiryListResponse;
import server.nadeliv.inquiry.dto.response.InquiryResponse;

public interface InquiryService {

    /**
     * Lambda 웹훅으로 들어온 수신 메일 저장.
     * @return 새로 저장되면 true, 이미 같은 messageId 가 있어 건너뛰면 false
     */
    boolean ingest(InboundMailRequest request);

    /**
     * 문의 목록 (최신 수신순, 페이징)
     * @param status all | unread | read
     */
    InquiryListResponse getInquiries(int page, int size, String status);

    /** 문의 상세 (본문 포함) */
    InquiryResponse getInquiry(String id);

    /** 읽음/안읽음 토글 */
    InquiryResponse updateRead(String id, boolean read, String adminUserId);

    /** soft delete */
    void delete(String id, String adminUserId);
}
