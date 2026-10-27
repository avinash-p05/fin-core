package com.fincore.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Counts failed sign-ins per client IP in Redis (fixed window, same atomic INCR + PEXPIRE script as
 * the fraud velocity counter).
 *
 * <p>If Redis is unavailable the limiter fails OPEN (sign-in still works, a warning is logged).
 * That is the opposite of the fraud rule on purpose: blocking every login during a Redis outage
 * would take the whole dashboard down, while passwords are still checked as normal.
 */
@Component
public class LoginAttemptLimiter {

    static final String KEY_PREFIX = "fincore:login-failures:";

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptLimiter.class);

    private static final RedisScript<Long> INCREMENT_WITH_TTL = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final StringRedisTemplate redis;
    private final SecurityProperties props;

    public LoginAttemptLimiter(StringRedisTemplate redis, SecurityProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /** True if this IP already used up its failed attempts for the current window. */
    public boolean isBlocked(String clientIp) {
        try {
            String value = redis.opsForValue().get(KEY_PREFIX + clientIp);
            return value != null && Long.parseLong(value) >= props.loginMaxFailures();
        } catch (RuntimeException e) {
            log.warn("Login limiter unavailable, allowing request: {}", e.getMessage());
            return false;
        }
    }

    public void recordFailure(String clientIp) {
        try {
            redis.execute(INCREMENT_WITH_TTL, List.of(KEY_PREFIX + clientIp),
                    String.valueOf(props.loginFailureWindow().toMillis()));
        } catch (RuntimeException e) {
            log.warn("Login limiter unavailable, failure not counted: {}", e.getMessage());
        }
    }
}
