package com.fincore.fraud;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Fixed-window transaction counter per source account in Redis.
 *
 * <p>INCR and EXPIRE run in one Lua script, which Redis executes atomically: concurrent payment
 * requests can never lose an increment, and the key can never be left without a TTL (which a
 * crash between separate INCR and EXPIRE calls could cause).
 */
@Component
public class VelocityTracker {

    static final String KEY_PREFIX = "fincore:velocity:";

    private static final RedisScript<Long> INCREMENT_WITH_TTL = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final StringRedisTemplate redis;

    public VelocityTracker(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Records one attempt and returns the number of attempts in the current window, including this one.
     *
     * @throws VelocityUnavailableException if Redis cannot be reached or errors
     */
    public long recordAndCount(UUID sourceAccountId, Duration window) {
        try {
            Long count = redis.execute(INCREMENT_WITH_TTL, List.of(KEY_PREFIX + sourceAccountId),
                    String.valueOf(window.toMillis()));
            if (count == null) {
                throw new VelocityUnavailableException("Redis returned no velocity count", null);
            }
            return count;
        } catch (VelocityUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            // Connection failures, timeouts and command errors all mean "unknown velocity".
            throw new VelocityUnavailableException("Velocity check unavailable: " + e.getMessage(), e);
        }
    }
}
