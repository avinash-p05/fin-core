package com.fincore.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fincore.common.Money;
import com.fincore.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "payments")
public class Payment extends BaseEntity {

    @Column(name = "source_account_id", nullable = false, updatable = false)
    private UUID sourceAccountId;

    @Column(name = "destination_account_id", nullable = false, updatable = false)
    private UUID destinationAccountId;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(updatable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "refunded_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal refundedAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    private long version;

    protected Payment() {
    }

    static Payment initiate(UUID source, UUID destination, BigDecimal amount, String description) {
        Payment p = new Payment();
        p.id = UUID.randomUUID();
        p.sourceAccountId = source;
        p.destinationAccountId = destination;
        p.amount = amount;
        p.currency = Money.CURRENCY;
        p.description = description;
        p.status = PaymentStatus.PENDING_REVIEW;
        p.refundedAmount = Money.zero();
        p.createdAt = Instant.now();
        p.updatedAt = p.createdAt;
        return p;
    }

    void markCompleted() {
        status = PaymentStatus.COMPLETED;
        completedAt = Instant.now();
        updatedAt = completedAt;
    }

    void markFailed(PaymentFailureReason reason) {
        status = PaymentStatus.FAILED;
        failureReason = reason.name();
        updatedAt = Instant.now();
    }

    void markDeclined(PaymentFailureReason reason) {
        status = PaymentStatus.DECLINED;
        failureReason = reason.name();
        updatedAt = Instant.now();
    }

    void markPendingReview() {
        status = PaymentStatus.PENDING_REVIEW;
        updatedAt = Instant.now();
    }

    void recordRefund(BigDecimal refundAmount) {
        refundedAmount = refundedAmount.add(refundAmount);
        status = refundedAmount.compareTo(amount) == 0 ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
        updatedAt = Instant.now();
    }

    public BigDecimal refundableAmount() {
        return amount.subtract(refundedAmount);
    }

    public UUID getSourceAccountId() {
        return sourceAccountId;
    }

    public UUID getDestinationAccountId() {
        return destinationAccountId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getDescription() {
        return description;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public BigDecimal getRefundedAmount() {
        return refundedAmount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
