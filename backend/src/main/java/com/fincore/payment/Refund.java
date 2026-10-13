package com.fincore.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fincore.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/** A refund is final once posted; corrections would be new compensating transactions. */
@Entity
@Immutable
@Table(name = "refunds")
public class Refund extends BaseEntity {

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Refund() {
    }

    Refund(UUID paymentId, BigDecimal amount, String reason) {
        this.id = UUID.randomUUID();
        this.paymentId = paymentId;
        this.amount = amount;
        this.reason = reason;
        this.createdAt = Instant.now();
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
