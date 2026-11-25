package com.fincore.ai;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fincore.account.AccountStatus;
import com.fincore.account.AccountType;
import com.fincore.fraud.FraudAlertStatus;
import com.fincore.fraud.FraudDecision;
import com.fincore.fraud.FraudRule;
import com.fincore.payment.PaymentStatus;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Data the copilot sees and returns. The views are small read-only records (never JPA entities),
 * so the model gets exactly these fields and nothing lazy-loaded or customer-identifying.
 */
public final class CopilotDtos {

    private CopilotDtos() {
    }

    /** A payment as shown to the model. {@code description} is customer-supplied text. */
    public record PaymentView(UUID id, UUID sourceAccountId, UUID destinationAccountId, BigDecimal amount,
                              String currency, PaymentStatus status, BigDecimal refundedAmount,
                              Integer fraudScore, FraudDecision fraudDecision, List<FraudRule> fraudRules,
                              String description, Instant createdAt) {
    }

    public record AccountView(UUID id, AccountType type, AccountStatus status, String currency, BigDecimal balance,
                              Instant openedAt, long ageDays) {
    }

    /** Settled payments from the alert's source account to its destination, before this one. */
    public record BeneficiaryHistory(UUID sourceAccountId, UUID destinationAccountId, long settledPayments,
                                     Instant firstSettledAt, Instant lastSettledAt) {
    }

    /** The alert under review plus the rule thresholds, so the model can explain why rules fired. */
    public record AlertView(UUID id, FraudAlertStatus status, FraudDecision decision, int riskScore,
                            List<FraudRule> triggeredRules, Instant createdAt, PaymentView payment,
                            int reviewThreshold, int declineThreshold, BigDecimal highValueThreshold) {
    }

    public enum SuggestedAction {
        APPROVE, REJECT, ESCALATE
    }

    public record Citation(
            @JsonPropertyDescription("Id of a payment returned by a tool")
            UUID paymentId,
            @JsonPropertyDescription("That payment's amount exactly as the tool returned it")
            BigDecimal amount) {
    }

    /** What the model must return (Spring AI structured output). Untrusted until validated. */
    public record CopilotDraft(
            @JsonPropertyDescription("Case summary for the analyst, 3 to 6 sentences, facts from tools only")
            String summary,
            @JsonPropertyDescription("Short risk factors, one per entry")
            List<String> riskFactors,
            @JsonPropertyDescription("APPROVE, REJECT or ESCALATE")
            SuggestedAction suggestedAction,
            @JsonPropertyDescription("Confidence in the suggestion, 0.0 to 1.0")
            Double confidence,
            @JsonPropertyDescription("Every payment mentioned in summary or riskFactors, with its exact amount")
            List<Citation> citations) {
    }

    public enum CopilotOutcome {
        /** Every citation, id and amount checked out against the database. */
        GROUNDED,
        /** The answer failed validation and was discarded; the suggestion is forced to ESCALATE. */
        VALIDATION_FAILED
    }

    /**
     * Advisory only. Nothing here changes the alert or the payment; the analyst still decides via
     * {@code PATCH /api/v1/fraud/alerts/{id}/status}.
     */
    public record CopilotResponse(UUID alertId, CopilotOutcome outcome, String summary, List<String> riskFactors,
                                  SuggestedAction suggestedAction, Double confidence, List<Citation> citations,
                                  List<String> validationErrors, String model, String promptVersion, int toolCalls,
                                  long latencyMs, Integer totalTokens, boolean cached) {

        CopilotResponse fromCache() {
            return new CopilotResponse(alertId, outcome, summary, riskFactors, suggestedAction, confidence, citations,
                    validationErrors, model, promptVersion, toolCalls, latencyMs, totalTokens, true);
        }
    }
}
