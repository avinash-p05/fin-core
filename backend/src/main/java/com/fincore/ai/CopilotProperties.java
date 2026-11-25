package com.fincore.ai;

import java.time.Duration;

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
        @Min(1) @Max(200) int maxHistoryPayments) {
}
