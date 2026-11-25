package com.fincore.ai;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.fincore.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Daily allowance of model calls, so public demo traffic cannot run up the LLM bill.
 *
 * <p>Counters live in Redis under {@code fincore:copilot:quota:<utc-date>:...}. One Lua script
 * checks every counter that applies to the caller and increments them only if all are below their
 * limits, so concurrent requests can never overshoot. A slot is reserved before the model is called
 * and is not refunded if the call later fails (tokens may already have been spent).
 *
 * <p>Fails closed: if Redis is unavailable the copilot answers 503 rather than call the model unmetered.
 */
@Component
public class CopilotQuota {

    private static final Logger log = LoggerFactory.getLogger(CopilotQuota.class);

    /** Counters expire a day after their UTC day ends. */
    private static final String TTL_SECONDS = String.valueOf(2 * 24 * 3600);

    /** Returns 0 when reserved, else the 1-based index of the first exhausted key. */
    private static final RedisScript<Long> RESERVE = new DefaultRedisScript<>("""
            for i, key in ipairs(KEYS) do
              local used = tonumber(redis.call('GET', key) or '0')
              if used >= tonumber(ARGV[i + 1]) then
                return i
              end
            end
            for _, key in ipairs(KEYS) do
              redis.call('INCR', key)
              redis.call('EXPIRE', key, ARGV[1])
            end
            return 0
            """, Long.class);

    /** Who is asking. Public callers are counted per IP because they share the demo login. */
    public record Caller(String username, boolean analyst, String ip) {
    }

    private final StringRedisTemplate redis;
    private final CopilotProperties.Quota limits;

    public CopilotQuota(StringRedisTemplate redis, CopilotProperties props) {
        this.redis = redis;
        this.limits = props.quota();
    }

    /** Takes one call from the caller's allowance, or throws 429 (used up) / 503 (cannot count). */
    public void reserve(Caller caller) {
        String day = LocalDate.now(ZoneOffset.UTC).toString();
        String prefix = "fincore:copilot:quota:" + day + ":";
        List<String> keys = new ArrayList<>();
        List<String> args = new ArrayList<>();
        args.add(TTL_SECONDS);
        if (caller.analyst()) {
            keys.add(prefix + "analyst:" + caller.username());
            args.add(String.valueOf(limits.analystPerDay()));
        } else {
            keys.add(prefix + "public");
            args.add(String.valueOf(limits.publicPerDay()));
            keys.add(prefix + "ip:" + caller.ip());
            args.add(String.valueOf(limits.publicPerIpPerDay()));
        }

        Long result;
        try {
            result = redis.execute(RESERVE, keys, args.toArray());
        } catch (RuntimeException e) {
            log.warn("Copilot quota unavailable; refusing the call: {}", e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "COPILOT_UNAVAILABLE",
                    "The copilot cannot check its usage limit right now; review the alert manually");
        }
        if (result == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "COPILOT_UNAVAILABLE",
                    "The copilot cannot check its usage limit right now; review the alert manually");
        }
        if (result == 0) {
            return;
        }
        String exhausted = keys.get(result.intValue() - 1);
        log.info("Copilot quota exhausted: {} (user={}, ip={})", exhausted, caller.username(), caller.ip());
        String message = caller.analyst()
                ? "You have used today's " + limits.analystPerDay() + " copilot calls"
                : result == 1
                        ? "The demo's copilot allowance for today is used up"
                        : "You have used today's " + limits.publicPerIpPerDay() + " copilot calls for this demo";
        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "COPILOT_QUOTA_EXCEEDED", message + "; it resets at 00:00 UTC");
    }
}
