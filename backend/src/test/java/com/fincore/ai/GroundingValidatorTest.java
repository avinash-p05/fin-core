package com.fincore.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fincore.account.AccountStatus;
import com.fincore.account.AccountType;
import com.fincore.ai.AlertScopedTools.Evidence;
import com.fincore.ai.CopilotDtos.AccountView;
import com.fincore.ai.CopilotDtos.AlertView;
import com.fincore.ai.CopilotDtos.Citation;
import com.fincore.ai.CopilotDtos.CopilotDraft;
import com.fincore.ai.CopilotDtos.PaymentView;
import com.fincore.ai.CopilotDtos.SuggestedAction;
import com.fincore.fraud.FraudAlertStatus;
import com.fincore.fraud.FraudDecision;
import com.fincore.fraud.FraudRule;
import com.fincore.payment.PaymentStatus;
import org.junit.jupiter.api.Test;

class GroundingValidatorTest {

    final UUID source = UUID.randomUUID();
    final UUID destination = UUID.randomUUID();
    final UUID outsider = UUID.randomUUID();

    final PaymentView held = payment(source, destination, "120000.00", PaymentStatus.PENDING_REVIEW);
    final PaymentView earlier = payment(source, destination, "2500.50", PaymentStatus.COMPLETED);
    final AccountView sourceAccount = new AccountView(source, AccountType.CUSTOMER, AccountStatus.ACTIVE, "INR",
            new BigDecimal("379990.00"), Instant.now(), 400);
    final AlertView alert = new AlertView(UUID.randomUUID(), FraudAlertStatus.OPEN, FraudDecision.REVIEW, 50,
            List.of(FraudRule.HIGH_VALUE), Instant.now(), held, 40, 70, new BigDecimal("100000.00"));
    final Evidence evidence = new Evidence(alert, Set.of(source, destination),
            Map.of(held.id(), held, earlier.id(), earlier), Map.of(source, sourceAccount));

    final GroundingValidator validator = new GroundingValidator();

    @Test
    void acceptsAnswerThatOnlyQuotesEvidence() {
        var draft = draft("Payment " + held.id() + " of INR 120000.00 is above the INR 1,00,000 high-value threshold. "
                        + "The source account " + source + " has balance ₹3,79,990.00 and paid this payee before ("
                        + earlier.id() + ", Rs. 2,500.50). 6 payments in 10 minutes; score 50.",
                cite(held.id(), "120000.00"), cite(earlier.id(), "2500.5"));

        var result = validator.validate(draft, evidence);

        assertThat(result.errors()).isEmpty();
        assertThat(result.grounded()).isTrue();
    }

    @Test
    void rejectsCitationOfPaymentNoToolReturned() {
        UUID invented = UUID.randomUUID();
        var result = validator.validate(draft("Looks fine.", cite(invented, "120000.00")), evidence);

        assertThat(result.grounded()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains(invented.toString()) && e.contains("no tool returned"));
    }

    @Test
    void rejectsWrongAmountEvenByOnePaisa() {
        var result = validator.validate(draft("Held payment.", cite(held.id(), "119999.99")), evidence);

        assertThat(result.grounded()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("119999.99") && e.contains("120000.00"));
    }

    @Test
    void treatsEqualAmountsWithDifferentScaleAsEqual() {
        var result = validator.validate(draft("Held payment.", cite(held.id(), "120000")), evidence);

        assertThat(result.grounded()).isTrue();
    }

    @Test
    void rejectsAmountInProseThatMatchesNothing() {
        var result = validator.validate(draft("Total sent today is INR 245000.00.", cite(held.id(), "120000.00")), evidence);

        assertThat(result.grounded()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("245000.00"));
    }

    @Test
    void readsLakhNotation() {
        assertThat(validator.validate(draft("About 1.2 lakh held.", cite(held.id(), "120000.00")), evidence).grounded())
                .isTrue();
        assertThat(validator.validate(draft("About 4.9L held.", cite(held.id(), "120000.00")), evidence).grounded())
                .isFalse();
    }

    @Test
    void rejectsUnknownIdInProse() {
        var result = validator.validate(draft("Linked to account " + outsider + ".", cite(held.id(), "120000.00")), evidence);

        assertThat(result.grounded()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains(outsider.toString()));
    }

    @Test
    void rejectsPaymentMentionedButNotCited() {
        var result = validator.validate(draft("See earlier payment " + earlier.id() + ".", cite(held.id(), "120000.00")),
                evidence);

        assertThat(result.grounded()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("without citing"));
    }

    @Test
    void rejectsPaymentOutsideTheAlertsAccounts() {
        PaymentView foreign = payment(outsider, UUID.randomUUID(), "50.00", PaymentStatus.COMPLETED);
        var widened = new Evidence(alert, evidence.scopeAccounts(), Map.of(held.id(), held, foreign.id(), foreign),
                evidence.accounts());

        var result = validator.validate(draft("Other activity.", cite(foreign.id(), "50.00")), widened);

        assertThat(result.grounded()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("does not involve"));
    }

    @Test
    void rejectsMissingFieldsAndBadConfidence() {
        var noAction = new CopilotDraft("Summary", List.of(), null, 0.5, List.of());
        var badConfidence = new CopilotDraft("Summary", List.of(), SuggestedAction.APPROVE, 1.7, List.of());
        var blank = new CopilotDraft(" ", List.of(), SuggestedAction.APPROVE, 0.5, List.of());

        assertThat(validator.validate(noAction, evidence).grounded()).isFalse();
        assertThat(validator.validate(badConfidence, evidence).grounded()).isFalse();
        assertThat(validator.validate(blank, evidence).grounded()).isFalse();
        assertThat(validator.validate(null, evidence).grounded()).isFalse();
    }

    @Test
    void checksRiskFactorsToo() {
        var draft = new CopilotDraft("Held for review.", List.of("Sent INR 999.00 yesterday"), SuggestedAction.ESCALATE,
                0.4, List.of(cite(held.id(), "120000.00")));

        assertThat(validator.validate(draft, evidence).grounded()).isFalse();
    }

    private CopilotDraft draft(String summary, Citation... citations) {
        return new CopilotDraft(summary, List.of("High value"), SuggestedAction.ESCALATE, 0.6, List.of(citations));
    }

    private static Citation cite(UUID id, String amount) {
        return new Citation(id, new BigDecimal(amount));
    }

    private static PaymentView payment(UUID from, UUID to, String amount, PaymentStatus status) {
        return new PaymentView(UUID.randomUUID(), from, to, new BigDecimal(amount), "INR", status, BigDecimal.ZERO,
                50, FraudDecision.REVIEW, List.of(FraudRule.HIGH_VALUE), "rent", Instant.now());
    }
}
