package server.nadeliv.users.component;

import lombok.Getter;
import org.springframework.security.core.AuthenticationException;

/**
 * 연속 로그인 실패로 아이디가 임시 잠긴 상태에서 로그인을 시도했을 때 던지는 예외.
 * Spring Security 의 {@code LockedException}(계정 영구 잠금)과 구분하기 위해 별도 타입으로 둔다.
 */
@Getter
public class LoginLockedException extends AuthenticationException {

    /** 잠금 해제까지 남은 시간(초) */
    private final long retryAfterSeconds;

    public LoginLockedException(String msg, long retryAfterSeconds) {
        super(msg);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
