package com.fincore.ai;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Fraud-review copilot limits, bound from {@code fincore.copilot.*}. */
@Validated
@ConfigurationProperties(prefix = "fincore.copilot")
public record CopilotProperties(
        @NotNull Duration timeout,
        @Min(1) @Max(50) int maxToolCalls,
        @Min(1) @Max(365) int maxHistoryDays,
        @Min(1) @Max(200) int maxHistoryPayments,
        @NotNull Duration cacheTtl,
        @Valid @NotNull Quota quota) {

    /**
     * Daily model-call allowances. Zero turns a group off entirely.
     *
     * @param publicPerDay      shared by every OPERATOR-role caller (the public demo login included)
     * @param publicPerIpPerDay per client IP within that shared pool
     * @param analystPerDay     per ANALYST user; not drawn from the public pool
     */
    public record Quota(@Min(0) int publicPerDay, @Min(0) int publicPerIpPerDay, @Min(0) int analystPerDay) {
    }
}
