package com.fincore.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import com.fincore.support.Concurrently;
import com.fincore.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Velocity counting against a real Redis container. */
class VelocityTrackerTest extends IntegrationTest {

    @Autowired
    VelocityTracker tracker;

    @Autowired
    StringRedisTemplate redis;

    @Test
    void concurrentIncrementsAreNotLostAndKeyExpires() throws Exception {
        UUID account = UUID.randomUUID();
        List<Callable<Long>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tasks.add(() -> tracker.recordAndCount(account, Duration.ofSeconds(60)));
        }

        List<Long> counts = Concurrently.run(tasks).stream().map(Concurrently.Outcome::value).toList();

        // Every caller saw a distinct count 1..50: no lost or duplicated increments.
        assertThat(counts).doesNotContainNull().containsExactlyInAnyOrderElementsOf(
                java.util.stream.LongStream.rangeClosed(1, 50).boxed().toList());
        Long ttlMs = redis.getExpire(VelocityTracker.KEY_PREFIX + account, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThat(ttlMs).isPositive().isLessThanOrEqualTo(60_000L);
    }

    @Test
    void windowResetsAfterExpiry() throws Exception {
        UUID account = UUID.randomUUID();
        tracker.recordAndCount(account, Duration.ofMillis(300));
        tracker.recordAndCount(account, Duration.ofMillis(300));
        Thread.sleep(600);
        assertThat(tracker.recordAndCount(account, Duration.ofMillis(300))).isEqualTo(1L);
    }

    @Test
    void unreachableRedisSurfacesAsVelocityUnavailable() {
        // Port 1 on localhost: connection refused, the same failure mode as a Redis outage.
        LettuceConnectionFactory factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 1),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(300)).build());
        factory.afterPropertiesSet();
        try {
            VelocityTracker broken = new VelocityTracker(new StringRedisTemplate(factory));
            long start = System.nanoTime();
            assertThatThrownBy(() -> broken.recordAndCount(UUID.randomUUID(), Duration.ofSeconds(60)))
                    .isInstanceOf(VelocityUnavailableException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
        } finally {
            factory.destroy();
        }
    }
}
