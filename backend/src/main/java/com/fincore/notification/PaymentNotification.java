package com.fincore.notification;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fincore.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "payment_notifications")
public class PaymentNotification extends BaseEntity {

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "notification_type", nullable = false)
    private String type;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PaymentNotification() {
    }

    PaymentNotification(UUID eventId, UUID accountId, UUID paymentId, String type, BigDecimal amount, String message) {
        this.id = UUID.randomUUID();
        this.eventId = eventId;
        this.accountId = accountId;
        this.paymentId = paymentId;
        this.type = type;
        this.amount = amount;
        this.message = message;
        this.createdAt = Instant.now();
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public String getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getMessage() {
        return message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
