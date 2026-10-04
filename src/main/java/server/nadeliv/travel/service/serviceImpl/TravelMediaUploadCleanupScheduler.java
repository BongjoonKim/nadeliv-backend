package server.nadeliv.travel.service.serviceImpl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import server.nadeliv.travel.service.TravelMediaUploadService;

/**
 * complete 되지 않고 만료된 presigned 업로드 세션 정리.
 * 멀티파트는 abort, 단일 PUT 으로 올라간 고아 객체는 삭제 (S3 lifecycle 규칙은 멀티파트 조각만 지우므로 보완).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TravelMediaUploadCleanupScheduler {

    private final TravelMediaUploadService travelMediaUploadService;

    // 매시 17분 (다른 정각 작업과 겹치지 않게)
    @Scheduled(cron = "0 17 * * * *")
    public void cleanupExpiredUploads() {
        try {
            int cleaned = travelMediaUploadService.cleanupExpiredUploads();
            if (cleaned > 0) {
                log.info("Expired travel media uploads cleaned: {}", cleaned);
            }
        } catch (Exception e) {
            log.error("Travel media upload cleanup failed: {}", e.getMessage(), e);
        }
    }
}
