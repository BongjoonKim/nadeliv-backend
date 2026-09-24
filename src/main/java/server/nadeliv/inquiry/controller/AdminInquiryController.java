package server.nadeliv.inquiry.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import server.nadeliv.inquiry.dto.response.InquiryListResponse;
import server.nadeliv.inquiry.dto.response.InquiryResponse;
import server.nadeliv.inquiry.service.InquiryService;
import server.nadeliv.users.dto.CustomUserDetails;

import java.util.Map;

/**
 * 관리자 문의함 API. AdminUserController 와 같이 @PreAuthorize 로 admin 권한을 강제한다.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/inquiries")
@PreAuthorize("hasAuthority('admin')")
public class AdminInquiryController {

    private final InquiryService inquiryService;

    /** 문의 목록 (status: all | unread | read) */
    @GetMapping("")
    public ResponseEntity<InquiryListResponse> getInquiries(
            @RequestParam(defaultValue = "0") Integer page,
            @RequestParam(defaultValue = "20") Integer size,
            @RequestParam(defaultValue = "all") String status) {
        return ResponseEntity.ok(inquiryService.getInquiries(page, size, status));
    }

    /** 문의 상세 (본문 포함) */
    @GetMapping("/{id}")
    public ResponseEntity<InquiryResponse> getInquiry(@PathVariable String id) {
        return ResponseEntity.ok(inquiryService.getInquiry(id));
    }

    /** 읽음/안읽음 변경 — body: {"read": true|false} */
    @PatchMapping("/{id}/read")
    public ResponseEntity<InquiryResponse> updateRead(
            @PathVariable String id,
            @RequestBody Map<String, Boolean> body,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        boolean read = body == null || !Boolean.FALSE.equals(body.get("read"));
        return ResponseEntity.ok(inquiryService.updateRead(id, read, userDetails.getUsername()));
    }

    /** soft delete */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            @PathVariable String id,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        inquiryService.delete(id, userDetails.getUsername());
        return ResponseEntity.ok(Map.of("success", true));
    }
}
