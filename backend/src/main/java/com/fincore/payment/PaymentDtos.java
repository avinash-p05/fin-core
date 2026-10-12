package com.fincore.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

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
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt) {

        public static PaymentResponse from(Payment p) {
            return new PaymentResponse(p.getId(), p.getSourceAccountId(), p.getDestinationAccountId(), p.getAmount(),
                    p.getCurrency(), p.getDescription(), p.getStatus(), p.getFailureReason(), p.getRefundedAmount(),
                    p.getCreatedAt(), p.getUpdatedAt(), p.getCompletedAt());
        }
    }

    /** Service result: the payment plus whether it was replayed from an earlier identical request. */
    public record PaymentResult(Payment payment, boolean replayed) {
    }
}
