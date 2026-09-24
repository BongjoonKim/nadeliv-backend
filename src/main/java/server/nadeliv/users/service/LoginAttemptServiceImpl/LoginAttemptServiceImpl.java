package server.nadeliv.users.service.LoginAttemptServiceImpl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import server.nadeliv.users.service.LoginAttemptService;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Redis 기반 로그인 실패 카운터 / 잠금 구현 (아이디 + IP).
 *
 * <pre>
 *  login:fail:user:{userId}  → 아이디 연속 실패 횟수. 마지막 실패 후 LOCK_SECONDS 유지
 *  login:lock:user:{userId}  → 존재하면 아이디 잠금. TTL = LOCK_SECONDS
 *  login:fail:ip:{ip}        → IP 누적 실패 횟수(아이디 무관). 마지막 실패 후 LOCK_SECONDS 유지
 *  login:lock:ip:{ip}        → 존재하면 IP 잠금. TTL = LOCK_SECONDS
 * </pre>
 *
 * <p><b>Fail-open 정책</b>: Redis 장애 시 예외를 삼키고 로그인을 허용한다. 잠금은 best-effort 방어이며
 * Redis 가 잠깐 흔들린다고 정상 사용자의 로그인이 막혀서는 안 되기 때문 (RateLimitService 와 동일 기조).
 *
 * <p>아이디 잠금은 제3자가 남의 아이디로 일부러 틀려 10분간 잠글 수 있는 트레이드오프가 있다.
 * IP 잠금은 그 공격자의 IP 도 함께 누적시켜 대량 시도 자체를 어렵게 만든다.
 * 운영은 ELB 뒤라 IP 는 X-Forwarded-For 기준(HttpRequestUtils.getClientIp)이다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LoginAttemptServiceImpl implements LoginAttemptService {

    private final StringRedisTemplate redisTemplate;

    private static final String USER_FAIL_PREFIX = "login:fail:user:";
    private static final String USER_LOCK_PREFIX = "login:lock:user:";
    private static final String IP_FAIL_PREFIX = "login:fail:ip:";
    private static final String IP_LOCK_PREFIX = "login:lock:ip:";
    private static final Duration LOCK_TTL = Duration.ofSeconds(LOCK_SECONDS);

    @Override
    public long getRemainingLockSeconds(String userId, String clientIp) {
        long userLock = remainingLock(USER_LOCK_PREFIX, normalizeUser(userId));
        long ipLock = remainingLock(IP_LOCK_PREFIX, normalizeIp(clientIp));
        return Math.max(userLock, ipLock);
    }

    @Override
    public void recordFailure(String userId, String clientIp) {
        incrementAndMaybeLock(USER_FAIL_PREFIX, USER_LOCK_PREFIX, normalizeUser(userId), MAX_FAILURES, "userId");
        incrementAndMaybeLock(IP_FAIL_PREFIX, IP_LOCK_PREFIX, normalizeIp(clientIp), IP_MAX_FAILURES, "ip");
    }

    @Override
    public void clearFailures(String userId) {
        String key = normalizeUser(userId);
        if (key == null) return;
        try {
            redisTemplate.delete(USER_FAIL_PREFIX + key);
            redisTemplate.delete(USER_LOCK_PREFIX + key);
        } catch (Exception e) {
            log.warn("로그인 실패 카운터 초기화 실패(무시): userId={}, err={}", key, e.getMessage());
        }
    }

    /** 잠금 키의 남은 TTL(초). 키 없음/TTL 없음/Redis 장애 → 0. */
    private long remainingLock(String lockPrefix, String key) {
        if (key == null) return 0L;
        try {
            Long ttl = redisTemplate.getExpire(lockPrefix + key, TimeUnit.SECONDS);
            // -2: 키 없음, -1: TTL 없음(비정상, 잠금 키는 항상 TTL 을 가짐) → 둘 다 잠금 아님으로 취급
            if (ttl == null || ttl <= 0) return 0L;
            return ttl;
        } catch (Exception e) {
            log.warn("로그인 잠금 조회 실패(fail-open, 허용): key={}{}, err={}", lockPrefix, key, e.getMessage());
            return 0L;
        }
    }

    /** 실패 카운터 1 증가. 기준 도달 시 잠금 키를 만들고 카운터를 지운다. */
    private void incrementAndMaybeLock(String failPrefix, String lockPrefix, String key, int limit, String label) {
        if (key == null) return;
        try {
            String failKey = failPrefix + key;
            Long count = redisTemplate.opsForValue().increment(failKey);
            // 매 실패마다 TTL 을 갱신 → "마지막 실패로부터 10분" 동안 연속 실패로 간주
            redisTemplate.expire(failKey, LOCK_TTL);

            if (count != null && count >= limit) {
                redisTemplate.opsForValue().set(lockPrefix + key, String.valueOf(count), LOCK_TTL);
                redisTemplate.delete(failKey);
                log.warn("로그인 {}회 실패 → {}초 잠금: {}={}", count, LOCK_SECONDS, label, key);
            }
        } catch (Exception e) {
            log.warn("로그인 실패 기록 실패(fail-open, 무시): {}={}, err={}", label, key, e.getMessage());
        }
    }

    /** 대소문자·공백 차이로 카운터가 분산되지 않도록 정규화. 빈 값이면 null. */
    private static String normalizeUser(String userId) {
        if (!StringUtils.hasText(userId)) return null;
        return userId.trim().toLowerCase();
    }

    /** IP 가 없거나 "unknown" 이면 null (카운트 불가 → IP 제한 미적용, 아이디 제한은 그대로 동작). */
    private static String normalizeIp(String clientIp) {
        if (!StringUtils.hasText(clientIp)) return null;
        String ip = clientIp.trim();
        if ("unknown".equalsIgnoreCase(ip)) return null;
        return ip;
    }
}
