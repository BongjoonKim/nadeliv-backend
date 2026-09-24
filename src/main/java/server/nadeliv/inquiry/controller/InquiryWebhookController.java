package server.nadeliv.inquiry.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import server.nadeliv.inquiry.dto.request.InboundMailRequest;
import server.nadeliv.inquiry.service.InquiryService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * SES 수신 메일 웹훅 (Lambda nadeliv-mail-forward → 이 엔드포인트).
 *
 * 공개 경로(ps)이지만 공유 시크릿 토큰(query param t)으로 인증한다 —
 * SesNotificationController 와 동일한 방식. 토큰 미설정/불일치 시 전부 거부(fail-closed).
 */
@RestController
@RequestMapping("/api/v1/inquiries")
@RequiredArgsConstructor
@Slf4j
public class InquiryWebhookController {

    private final InquiryService inquiryService;

    @Value("${cloud.aws.ses.inbound.secret-token:}")
    private String secretToken;

    @PostMapping("/ps/inbound")
    public ResponseEntity<?> receive(
            @RequestParam(value = "t", required = false) String token,
            @RequestBody InboundMailRequest request) {
        if (!isTokenValid(token)) {
            log.warn("inbound mail 웹훅 인증 실패 (토큰 미설정 또는 불일치)");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("success", false));
        }
        boolean created = inquiryService.ingest(request);
        return ResponseEntity.ok(Map.of("success", true, "created", created));
    }

    /** 상수 시간 비교 (타이밍 공격 방지). 토큰 미설정 시 항상 false. */
    private boolean isTokenValid(String provided) {
        if (secretToken == null || secretToken.isBlank() || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                secretToken.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
