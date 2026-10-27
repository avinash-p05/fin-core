package com.fincore.config;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Brute-force protection for HTTP Basic. Runs before Spring Security checks the password:
 * an IP with too many recent failures gets 429 immediately; otherwise the request continues and a
 * resulting 401 is counted as a failure.
 *
 * <p>{@code request.getRemoteAddr()} is the real client IP because {@code server.forward-headers-strategy}
 * trusts X-Forwarded-For from the internal proxies (Caddy, nginx).
 */
class LoginRateLimitFilter extends OncePerRequestFilter {

    private final LoginAttemptLimiter limiter;

    LoginRateLimitFilter(LoginAttemptLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Basic ")) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = request.getRemoteAddr();
        if (limiter.isBlocked(clientIp)) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader(HttpHeaders.RETRY_AFTER, "900");
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("""
                    {"type":"urn:fincore:error:too_many_login_attempts","title":"Too Many Requests","status":429,\
                    "detail":"Too many failed sign-in attempts. Try again later.","code":"TOO_MANY_LOGIN_ATTEMPTS"}""");
            return;
        }

        chain.doFilter(request, response);
        if (response.getStatus() == HttpStatus.UNAUTHORIZED.value()) {
            limiter.recordFailure(clientIp);
        }
    }
}
