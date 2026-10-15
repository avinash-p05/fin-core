package com.fincore.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.fincore.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent extends BaseEntity {

    @Column(name = "aggregate_type", nullable = false, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {
    }

    OutboxEvent(UUID id, String aggregateType, UUID aggregateId, String eventType, String payload, Instant now) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = now;
        this.createdAt = now;
    }

    void markPublished(Instant now) {
        status = OutboxStatus.PUBLISHED;
        attempts++;
        publishedAt = now;
        lastError = null;
    }

    /** Exponential backoff (initial * 2^(attempts-1), capped); FAILED after maxAttempts. */
    void markAttemptFailed(String error, Instant now, int maxAttempts, Duration initialBackoff, Duration maxBackoff) {
        attempts++;
        lastError = error == null ? "unknown error" : error.substring(0, Math.min(error.length(), 1000));
        if (attempts >= maxAttempts) {
            status = OutboxStatus.FAILED;
            return;
        }
        long factor = 1L << Math.min(attempts - 1, 20);
        Duration backoff = initialBackoff.multipliedBy(factor);
        nextAttemptAt = now.plus(backoff.compareTo(maxBackoff) > 0 ? maxBackoff : backoff);
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
