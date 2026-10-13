package com.fincore.fraud;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fincore.common.ApiException;
import com.fincore.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "fraud_alerts")
public class FraudAlert extends BaseEntity {

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "risk_score", nullable = false, updatable = false)
    private int riskScore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private FraudDecision decision;

    @Column(name = "triggered_rules", nullable = false, updatable = false)
    private String triggeredRules;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FraudAlertStatus status;

    @Column(name = "reviewed_by")
    private String reviewedBy;

    @Column(name = "review_note")
    private String reviewNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected FraudAlert() {
    }

    static FraudAlert open(UUID paymentId, FraudAssessment assessment) {
        if (assessment.decision() == FraudDecision.APPROVED) {
            throw new IllegalArgumentException("Alerts are only raised for REVIEW or DECLINED decisions");
        }
        FraudAlert a = new FraudAlert();
        a.id = UUID.randomUUID();
        a.paymentId = paymentId;
        a.riskScore = assessment.score();
        a.decision = assessment.decision();
        a.triggeredRules = assessment.rulesAsString();
        a.status = FraudAlertStatus.OPEN;
        a.createdAt = Instant.now();
        a.updatedAt = a.createdAt;
        return a;
    }

    /**
     * Permitted transitions. REVIEW alerts gate a held payment (APPROVED releases it, REJECTED
     * declines it). DECLINED alerts are informational: the payment is final, they can only be CLOSED.
     */
    public Set<FraudAlertStatus> allowedTransitions() {
        if (status.isTerminal()) {
            return EnumSet.noneOf(FraudAlertStatus.class);
        }
        EnumSet<FraudAlertStatus> next = decision == FraudDecision.REVIEW
                ? EnumSet.of(FraudAlertStatus.APPROVED, FraudAlertStatus.REJECTED)
                : EnumSet.of(FraudAlertStatus.CLOSED);
        if (status == FraudAlertStatus.OPEN) {
            next.add(FraudAlertStatus.IN_REVIEW);
        }
        return next;
    }

    void transitionTo(FraudAlertStatus target, String reviewer, String note) {
        if (!allowedTransitions().contains(target)) {
            throw ApiException.conflict("INVALID_ALERT_TRANSITION",
                    "Cannot move a " + decision + " alert from " + status + " to " + target);
        }
        status = target;
        reviewedBy = reviewer;
        if (note != null && !note.isBlank()) {
            reviewNote = note.trim();
        }
        updatedAt = Instant.now();
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public int getRiskScore() {
        return riskScore;
    }

    public FraudDecision getDecision() {
        return decision;
    }

    public List<FraudRule> getTriggeredRules() {
        return FraudAssessment.parseRules(triggeredRules);
    }

    public FraudAlertStatus getStatus() {
        return status;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
