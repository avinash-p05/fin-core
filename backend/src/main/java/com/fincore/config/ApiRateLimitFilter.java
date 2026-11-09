package com.fincore.config;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-IP request limit for every {@code /api/**} call (previously done by nginx's limit_req).
 *
 * <p>Fixed one-second window in Redis: key {@code fincore:api-rate:<ip>:<epochSecond>}, incremented
 * atomically by a Lua script that also sets a short TTL. Because the counter lives in Redis, all
 * backend instances share it. If Redis is unavailable the filter fails open (requests pass, a
 * warning is logged); the failed-login limiter and fraud rules are unaffected.
 */
class ApiRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiRateLimitFilter.class);

    private static final RedisScript<Long> INCREMENT_WITH_TTL = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final StringRedisTemplate redis;
    private final int limitPerSecond;

    ApiRateLimitFilter(StringRedisTemplate redis, int limitPerSecond) {
        this.redis = redis;
        this.limitPerSecond = limitPerSecond;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long second = System.currentTimeMillis() / 1000;
        String key = "fincore:api-rate:" + request.getRemoteAddr() + ":" + second;
        Long count;
        try {
            count = redis.execute(INCREMENT_WITH_TTL, List.of(key), "2000");
        } catch (RuntimeException e) {
            log.warn("API rate limiter unavailable, allowing request: {}", e.getMessage());
            count = null;
        }

        if (count != null && count > limitPerSecond) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader(HttpHeaders.RETRY_AFTER, "1");
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("""
                    {"type":"urn:fincore:error:rate_limited","title":"Too Many Requests","status":429,\
                    "detail":"Too many requests. Slow down and try again.","code":"RATE_LIMITED"}""");
            return;
        }
        chain.doFilter(request, response);
    }
}
