package com.fincore.fraud;

import java.math.BigDecimal;
import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Fraud rule thresholds and scores, bound from {@code fincore.fraud.*}. */
@Validated
@ConfigurationProperties(prefix = "fincore.fraud")
public record FraudProperties(
        @Min(1) int reviewThreshold,
        @Min(1) int declineThreshold,
        @Valid @NotNull HighValue highValue,
        @Valid @NotNull Velocity velocity,
        @Valid @NotNull NewBeneficiary newBeneficiary) {

    public record HighValue(boolean enabled, @NotNull BigDecimal threshold, @Min(0) int score) {
    }

    public record Velocity(boolean enabled, @Min(1) int maxTransactions, @NotNull Duration window, @Min(0) int score,
                           @NotNull FraudDecision unavailableDecision) {

        @AssertTrue(message = "unavailable-decision must be REVIEW or DECLINED; Redis outages must never auto-approve")
        boolean isUnavailableDecisionFailClosed() {
            return unavailableDecision != FraudDecision.APPROVED;
        }
    }

    public record NewBeneficiary(boolean enabled, @Min(0) int score) {
    }

    @AssertTrue(message = "decline-threshold must be greater than review-threshold")
    boolean isThresholdOrderValid() {
        return declineThreshold > reviewThreshold;
    }
}
