package com.fincore.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fincore.fraud.FraudDecision;
import com.fincore.fraud.FraudRule;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class PaymentDtos {

    private PaymentDtos() {
    }

    public record CreatePaymentRequest(
            @NotNull UUID sourceAccountId,
            @NotNull UUID destinationAccountId,
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
            @Size(max = 500) String description) {
    }

    public record PaymentResponse(
            UUID id,
            UUID sourceAccountId,
            UUID destinationAccountId,
            BigDecimal amount,
            String currency,
            String description,
            PaymentStatus status,
            String failureReason,
            BigDecimal refundedAmount,
            FraudSummary fraud,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt) {

        public static PaymentResponse from(Payment p) {
            return new PaymentResponse(p.getId(), p.getSourceAccountId(), p.getDestinationAccountId(), p.getAmount(),
                    p.getCurrency(), p.getDescription(), p.getStatus(), p.getFailureReason(), p.getRefundedAmount(),
                    p.getFraudDecision() == null ? null
                            : new FraudSummary(p.getFraudScore(), p.getFraudDecision(), p.getFraudRules()),
                    p.getCreatedAt(), p.getUpdatedAt(), p.getCompletedAt());
        }
    }

    public record FraudSummary(int score, FraudDecision decision, List<FraudRule> triggeredRules) {
    }

    public record RefundRequest(
            @DecimalMin(value = "0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
            @Size(max = 500) String reason) {
    }

    public record RefundResponse(
            UUID id,
            UUID paymentId,
            BigDecimal amount,
            String reason,
            Instant createdAt,
            PaymentStatus paymentStatus,
            BigDecimal paymentRefundedAmount) {

        public static RefundResponse from(Refund r, Payment p) {
            return new RefundResponse(r.getId(), r.getPaymentId(), r.getAmount(), r.getReason(), r.getCreatedAt(),
                    p.getStatus(), p.getRefundedAmount());
        }
    }

    public record RefundResult(Refund refund, Payment payment, boolean replayed) {
    }

    /** Service result: the payment plus whether it was replayed from an earlier identical request. */
    public record PaymentResult(Payment payment, boolean replayed) {
    }
}
