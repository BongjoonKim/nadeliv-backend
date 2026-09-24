package server.nadeliv.users.service;

/**
 * 로그인 실패 횟수 추적 및 임시 잠금 (아이디 기준 + IP 기준 이중 방어).
 *
 * <ul>
 *   <li><b>아이디 기준</b>: 같은 아이디로 {@link #MAX_FAILURES}회 연속 실패 → {@link #LOCK_SECONDS}초 잠금.
 *       특정 계정을 노린 비밀번호 무차별 대입 방지.</li>
 *   <li><b>IP 기준</b>: 같은 IP에서 아이디를 바꿔가며 총 {@link #IP_MAX_FAILURES}회 실패 → {@link #LOCK_SECONDS}초 잠금.
 *       아이디 목록을 돌려가며 시도하는 공격(credential stuffing) 방지. 공유망(회사·카페 NAT) 오차단을
 *       줄이기 위해 아이디 기준보다 넉넉하게 잡는다.</li>
 * </ul>
 * 로그인에 성공하면 해당 아이디의 카운터만 초기화된다. IP 카운터는 초기화하지 않는다
 * (공격자가 아는 계정 하나로 로그인해 IP 카운터를 리셋하는 우회 방지).
 */
public interface LoginAttemptService {

    /** 아이디 잠금 발동 기준 연속 실패 횟수 */
    int MAX_FAILURES = 10;

    /** IP 잠금 발동 기준 실패 횟수 (아이디 무관 누적) */
    int IP_MAX_FAILURES = 30;

    /** 잠금 유지 시간(초) — 10분 (아이디·IP 공통) */
    long LOCK_SECONDS = 600L;

    /**
     * 아이디 또는 IP 가 잠금 상태이면 남은 잠금 시간(초, 1 이상)을, 아니면 0 을 반환.
     * 둘 다 잠겨 있으면 더 긴 쪽을 반환. Redis 장애 시 0(허용)으로 fail-open.
     */
    long getRemainingLockSeconds(String userId, String clientIp);

    /**
     * 실패 1회 기록 (아이디 카운터·IP 카운터 모두 증가).
     * 각 카운터가 기준에 도달하면 해당 키를 잠그고 카운터를 초기화한다.
     */
    void recordFailure(String userId, String clientIp);

    /**
     * 로그인 성공 시 해당 아이디의 실패 카운터·잠금 제거. IP 카운터는 유지.
     */
    void clearFailures(String userId);
}
