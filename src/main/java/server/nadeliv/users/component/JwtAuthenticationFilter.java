package server.nadeliv.users.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import server.nadeliv.users.service.CustomUserDetailsServiceImpl.CustomUserDetailService;
import server.nadeliv.utils.JwtUtil;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Autowired
    private JwtUtil jwtTokenUtil;

    @Autowired
    private CustomUserDetailService customUserDetailService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String path = request.getRequestURI();
        String method = request.getMethod();

        // 인증이 필요 없는 경로들
        if (shouldSkipAuthentication(path, method)) {
            chain.doFilter(request, response);
            return;
        }

        // 토큰 추출
        String accessToken = extractToken(request);

        // 토큰이 없는 경우 - 그냥 진행
        if (accessToken == null) {
            chain.doFilter(request, response);
            return;
        }

        try {
            Claims claims = jwtTokenUtil.verifyToken(accessToken);
            String username = claims.getSubject();

            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = customUserDetailService.loadUserByUsername(username);

                // 탈퇴(비활성)한 계정의 access 토큰은 만료 전이라도 인증하지 않는다
                if (userDetails.isEnabled() && jwtTokenUtil.validateToken(accessToken, userDetails)) {
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    userDetails, null, userDetails.getAuthorities());
                    authentication.setDetails(
                            new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);

                    log.debug("Authentication set for user: {}", username);
                }
            }

        } catch (ExpiredJwtException e) {
            // 만료된 토큰 - 로그만 남기고 에러 응답하지 않음 (서버 시작 시 문제 방지)
            log.debug("JWT Token has expired for path: {}", path);

            // API 요청인 경우에만 에러 응답
            if (path.startsWith("/api/") && !path.startsWith("/api/auth/")) {
                sendUnauthorizedError(response, "Token expired");
                return;
            }

        } catch (JwtException e) {
            log.debug("Invalid JWT Token for path: {}", path);

            if (path.startsWith("/api/") && !path.startsWith("/api/auth/")) {
                sendUnauthorizedError(response, "Invalid token");
                return;
            }

        } catch (Exception e) {
            log.debug("Unable to process JWT Token: {}", e.getMessage());
        }

        // 모든 경우에 다음 필터로 진행
        chain.doFilter(request, response);
    }

    private boolean shouldSkipAuthentication(String path, String method) {
        return "OPTIONS".equalsIgnoreCase(method) ||
                path.equals("/") ||
                path.equals("/favicon.ico") ||
                path.equals("/health") ||
                path.startsWith("/actuator") ||
                path.startsWith("/api/auth/login") ||
                path.startsWith("/api/auth/register") ||
                path.startsWith("/api/auth/refresh") ||
                path.startsWith("/api/public/") ||
                path.startsWith("/ws/**") ||  // WebSocket
                path.startsWith("/static/") ||  // 정적 리소스
                path.startsWith("/css/") ||
                path.startsWith("/js/") ||
                path.startsWith("/images/");
    }

    private String extractToken(HttpServletRequest request) {
        String requestTokenHeader = request.getHeader("Authorization");
        if (requestTokenHeader != null && requestTokenHeader.startsWith("Bearer ")) {
            return requestTokenHeader.substring(7);
        }

        String tokenParam = request.getParameter("token");
        if (tokenParam != null && !tokenParam.isEmpty()) {
            log.debug("Token extracted from URL parameter");
            return tokenParam;
        }

        return null;
    }

    private void sendUnauthorizedError(HttpServletResponse response, String message)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");

        Map<String, Object> errorDetails = new LinkedHashMap<>();
        errorDetails.put("error", "Unauthorized");
        errorDetails.put("message", message);
        errorDetails.put("status", 401);
        errorDetails.put("timestamp", System.currentTimeMillis());

        ObjectMapper mapper = new ObjectMapper();
        mapper.writeValue(response.getWriter(), errorDetails);
    }
}