package com.fincore.outbox;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fincore.outbox")
public record OutboxProperties(
        boolean publisherEnabled,
        Duration pollInterval,
        int batchSize,
        int maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff,
        Duration sendTimeout) {
}
