package com.fincore.ai;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

import com.fincore.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;

class CopilotQuotaTest {

    final CopilotProperties props = new CopilotProperties(Duration.ofSeconds(30), 8, 30, 25, Duration.ofHours(6),
            new CopilotProperties.Quota(20, 3, 50));

    @Test
    @SuppressWarnings("unchecked")
    void refusesWhenRedisIsDownInsteadOfCallingTheModelUnmetered() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("Connection refused"));
        CopilotQuota quota = new CopilotQuota(redis, props);

        assertThatThrownBy(() -> quota.reserve(new CopilotQuota.Caller("demo", false, "203.0.113.1")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    org.assertj.core.api.Assertions.assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    org.assertj.core.api.Assertions.assertThat(e.getCode()).isEqualTo("COPILOT_UNAVAILABLE");
                });
    }
}
