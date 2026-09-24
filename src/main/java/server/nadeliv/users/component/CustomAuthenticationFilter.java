package server.nadeliv.users.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Component;
import server.nadeliv.error.ErrorCode;
import server.nadeliv.users.dto.TokenDTO;
import server.nadeliv.users.service.LoginAttemptService;
import server.nadeliv.utils.HttpRequestUtils;
import server.nadeliv.utils.JwtUtil;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class CustomAuthenticationFilter extends UsernamePasswordAuthenticationFilter {

    @Autowired
    @Lazy
    private AuthenticationManager authenticationManager;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private LoginAttemptService loginAttemptService;

    @Override
    @Autowired
    public void setAuthenticationManager(AuthenticationManager authenticationManager) {
        super.setAuthenticationManager(authenticationManager);
    }

    public CustomAuthenticationFilter(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
        setFilterProcessesUrl("/ps/login");
    }

    @Override
    public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response)
            throws AuthenticationException {
        System.out.println("Attempting authentication for request: " + request.getRequestURI());

        String username = request.getParameter("username");
        String password = request.getParameter("password");

        System.out.println("Username: " + username); // 디버깅용

        // 연속 실패로 잠긴 아이디 또는 IP 는 인증(DB 조회·비밀번호 해시 비교) 전에 차단
        String clientIp = HttpRequestUtils.getClientIp(request);
        long remainingLock = loginAttemptService.getRemainingLockSeconds(username, clientIp);
        if (remainingLock > 0) {
            throw new LoginLockedException(ErrorCode.LOGIN_ATTEMPTS_EXCEEDED.getMsg(), remainingLock);
        }

        UsernamePasswordAuthenticationToken authenticationToken =
                new UsernamePasswordAuthenticationToken(username, password);

        // 인증 수행
        Authentication authentication = authenticationManager.authenticate(authenticationToken);

        // ✅ 핵심 수정: authentication 객체를 저장 (authenticationToken이 아님!)
        SecurityContextHolder.getContext().setAuthentication(authentication);

        System.out.println("Authentication successful for user: " + username);

        return authentication;
    }

    @Override
    protected void successfulAuthentication(HttpServletRequest request,
                                            HttpServletResponse response,
                                            FilterChain chain,
                                            Authentication authResult) throws IOException {
        System.out.println("Processing successful authentication");

        UserDetails userDetails = (UserDetails) authResult.getPrincipal();

        // 로그인 성공 → 연속 실패 카운터 초기화
        loginAttemptService.clearFailures(userDetails.getUsername());

        String accessToken = jwtUtil.generateAccessToken(userDetails.getUsername());
        String refreshToken = jwtUtil.generateRefreshToken(userDetails.getUsername());

        TokenDTO tokenDTO = new TokenDTO();
        tokenDTO.setAccessToken(accessToken);
        tokenDTO.setRefreshToken(refreshToken);
        tokenDTO.setGrantType(userDetails.getAuthorities());

        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setStatus(HttpStatus.OK.value());
        response.setHeader("accessToken", accessToken);
        response.setHeader("refreshToken", refreshToken);
        response.setHeader("authorities", userDetails.getAuthorities().toString());

        new ObjectMapper().writeValue(response.getOutputStream(), tokenDTO);

        System.out.println("Token generated successfully for user: " + userDetails.getUsername());
    }

    @Override
    protected void unsuccessfulAuthentication(HttpServletRequest request,
                                              HttpServletResponse response,
                                              AuthenticationException failed)
            throws IOException, ServletException {
        String username = request.getParameter("username");
        String clientIp = HttpRequestUtils.getClientIp(request);

        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();

        if (failed instanceof LoginLockedException locked) {
            // 잠금 상태에서의 시도: 실패 카운트에 포함하지 않고 429 + Retry-After 로 응답
            System.err.println("Login blocked (locked): user=" + username + ", ip=" + clientIp
                    + ", retryAfter=" + locked.getRetryAfterSeconds() + "s");

            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(locked.getRetryAfterSeconds()));

            body.put("code", ErrorCode.LOGIN_ATTEMPTS_EXCEEDED.getCode());
            body.put("error", "Too many failed login attempts");
            body.put("message", ErrorCode.LOGIN_ATTEMPTS_EXCEEDED.getMsg());
            body.put("retryAfterSeconds", locked.getRetryAfterSeconds());
            new ObjectMapper().writeValue(response.getOutputStream(), body);
            return;
        }

        System.err.println("Authentication failed: user=" + username + ", ip=" + clientIp
                + ", reason=" + failed.getMessage());

        // 실패 기록: 아이디·IP 카운터 모두 증가 (기준 도달 시 서비스가 잠금을 건다)
        loginAttemptService.recordFailure(username, clientIp);

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        body.put("code", HttpStatus.UNAUTHORIZED.value());
        body.put("error", failed.getMessage());
        body.put("message", "Invalid username or password");

        new ObjectMapper().writeValue(response.getOutputStream(), body);
    }
}
