package com.fincore.ai;

import java.util.Optional;
import java.util.UUID;

import com.fincore.ai.CopilotDtos.CopilotResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reuses a grounded answer for an alert for {@code fincore.copilot.cache-ttl}, so repeat clicks
 * cost no tokens and no quota. Only GROUNDED answers are stored. Best effort: a Redis problem is a
 * cache miss (the quota check, which fails closed, still protects the model).
 */
@Component
public class CopilotAnswerCache {

    private static final Logger log = LoggerFactory.getLogger(CopilotAnswerCache.class);

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final CopilotProperties props;

    public CopilotAnswerCache(StringRedisTemplate redis, JsonMapper json, CopilotProperties props) {
        this.redis = redis;
        this.json = json;
        this.props = props;
    }

    public Optional<CopilotResponse> get(UUID alertId) {
        try {
            String cached = redis.opsForValue().get(key(alertId));
            return cached == null ? Optional.empty() : Optional.of(json.readValue(cached, CopilotResponse.class));
        } catch (RuntimeException e) {
            log.warn("Copilot cache read failed for alert {}: {}", alertId, e.getMessage());
            return Optional.empty();
        }
    }

    public void put(CopilotResponse response) {
        try {
            redis.opsForValue().set(key(response.alertId()), json.writeValueAsString(response), props.cacheTtl());
        } catch (RuntimeException e) {
            log.warn("Copilot cache write failed for alert {}: {}", response.alertId(), e.getMessage());
        }
    }

    private static String key(UUID alertId) {
        return "fincore:copilot:answer:" + alertId;
    }
}
