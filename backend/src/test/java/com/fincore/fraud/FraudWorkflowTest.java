package com.fincore.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import com.fincore.account.Account;
import com.fincore.common.ApiException;
import com.fincore.ledger.LedgerDtos.VerificationStatus;
import com.fincore.payment.Payment;
import com.fincore.payment.PaymentStatus;
import com.fincore.support.Concurrently;
import com.fincore.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** End-to-end fraud outcomes with real PostgreSQL and Redis. */
@AutoConfigureMockMvc
class FraudWorkflowTest extends IntegrationTest {

    @Autowired
    FraudAlertRepository alertRepository;

    @Autowired
    FraudReviewService reviews;

    @Autowired
    MockMvc mvc;

    @Test
    void reviewDecisionHoldsPaymentWithoutMovingMoney() {
        Account payer = newAccount("500000.00");
        Account payee = newAccount("0");
        establishBeneficiary(payer, payee);

        Payment held = pay(payer, payee, "120000.00"); // HIGH_VALUE (50) => REVIEW

        assertThat(held.getStatus()).isEqualTo(PaymentStatus.PENDING_REVIEW);
        assertThat(held.getFraudDecision()).isEqualTo(FraudDecision.REVIEW);
        assertThat(held.getFraudScore()).isEqualTo(50);
        assertThat(held.getFraudRules()).containsExactly(FraudRule.HIGH_VALUE);
        assertThat(balanceOf(payer)).isEqualByComparingTo("499990.00");
        assertThat(balanceOf(payee)).isEqualByComparingTo("10.00");
        assertThat(ledgerQueryService.forPayment(held.getId())).isEmpty();

        FraudAlert alert = alertRepository.findByPaymentId(held.getId()).orElseThrow();
        assertThat(alert.getStatus()).isEqualTo(FraudAlertStatus.OPEN);
        assertThat(alert.allowedTransitions()).containsExactlyInAnyOrder(
                FraudAlertStatus.IN_REVIEW, FraudAlertStatus.APPROVED, FraudAlertStatus.REJECTED);
    }

    @Test
    void approvingReviewExecutesTheTransferOnce() {
        Account payer = newAccount("500000.00");
        Account payee = newAccount("0");
        establishBeneficiary(payer, payee);
        Payment held = pay(payer, payee, "120000.00");
        FraudAlert alert = alertRepository.findByPaymentId(held.getId()).orElseThrow();

        reviews.updateStatus(alert.getId(), FraudAlertStatus.IN_REVIEW, "looking", "analyst");
        var outcome = reviews.updateStatus(alert.getId(), FraudAlertStatus.APPROVED, "customer confirmed", "analyst");

        assertThat(outcome.payment().getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(outcome.alert().getReviewedBy()).isEqualTo("analyst");
        assertThat(balanceOf(payer)).isEqualByComparingTo("379990.00");
        assertThat(balanceOf(payee)).isEqualByComparingTo("120010.00");
        assertThat(ledgerQueryService.forPayment(held.getId())).hasSize(1);
        assertThat(ledgerQueryService.verifyLedger().status()).isEqualTo(VerificationStatus.VALID);

        // A second approval is rejected; the payment is not executed twice.
        assertThatThrownBy(() -> reviews.updateStatus(alert.getId(), FraudAlertStatus.APPROVED, null, "analyst"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(409));
        assertThat(balanceOf(payer)).isEqualByComparingTo("379990.00");
    }

    @Test
    void concurrentApprovalsExecuteTheHeldPaymentOnce() throws Exception {
        Account payer = newAccount("500000.00");
        Account payee = newAccount("0");
        establishBeneficiary(payer, payee);
        Payment held = pay(payer, payee, "120000.00");
        FraudAlert alert = alertRepository.findByPaymentId(held.getId()).orElseThrow();

        List<Callable<FraudReviewService.ReviewOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> reviews.updateStatus(alert.getId(), FraudAlertStatus.APPROVED, null, "analyst"));
        }
        var outcomes = Concurrently.run(tasks);

        assertThat(outcomes.stream().filter(o -> o.error() == null)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> o.error() instanceof ApiException)).hasSize(4);
        assertThat(balanceOf(payee)).isEqualByComparingTo("120010.00");
    }

    @Test
    void rejectingReviewDeclinesWithoutMovingMoney() {
        Account payer = newAccount("500000.00");
        Account payee = newAccount("0");
        establishBeneficiary(payer, payee);
        Payment held = pay(payer, payee, "120000.00");
        FraudAlert alert = alertRepository.findByPaymentId(held.getId()).orElseThrow();

        var outcome = reviews.updateStatus(alert.getId(), FraudAlertStatus.REJECTED, "suspicious", "analyst");

        assertThat(outcome.payment().getStatus()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(outcome.payment().getFailureReason()).isEqualTo("FRAUD_REVIEW_REJECTED");
        assertThat(balanceOf(payer)).isEqualByComparingTo("499990.00");
        assertThat(ledgerQueryService.forPayment(held.getId())).isEmpty();
    }

    @Test
    void approvedReviewStillFailsIfFundsWereSpentMeanwhile() {
        Account payer = newAccount("150000.00");
        Account payee = newAccount("0");
        Account other = newAccount("0");
        establishBeneficiary(payer, payee);
        Payment held = pay(payer, payee, "120000.00");
        pay(payer, other, "60000.00"); // approved (new beneficiary only), drains funds

        FraudAlert alert = alertRepository.findByPaymentId(held.getId()).orElseThrow();
        var outcome = reviews.updateStatus(alert.getId(), FraudAlertStatus.APPROVED, null, "analyst");

        assertThat(outcome.payment().getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(outcome.payment().getFailureReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(balanceOf(payee)).isEqualByComparingTo("10.00");
    }

    @Test
    void declinedPaymentNeverChangesBalancesAndAlertCanOnlyBeClosed() {
        Account payer = newAccount("500000.00");
        Account payee = newAccount("0");

        Payment declined = pay(payer, payee, "150000.00"); // HIGH_VALUE + NEW_BENEFICIARY = 70

        assertThat(declined.getStatus()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(declined.getFailureReason()).isEqualTo("FRAUD_DECLINED");
        assertThat(declined.getFraudRules()).containsExactlyInAnyOrder(FraudRule.HIGH_VALUE, FraudRule.NEW_BENEFICIARY);
        assertThat(balanceOf(payer)).isEqualByComparingTo("500000.00");
        assertThat(balanceOf(payee)).isEqualByComparingTo("0");

        FraudAlert alert = alertRepository.findByPaymentId(declined.getId()).orElseThrow();
        assertThatThrownBy(() -> reviews.updateStatus(alert.getId(), FraudAlertStatus.APPROVED, null, "analyst"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_ALERT_TRANSITION"));
        var closed = reviews.updateStatus(alert.getId(), FraudAlertStatus.CLOSED, "confirmed fraud", "analyst");
        assertThat(closed.alert().getStatus()).isEqualTo(FraudAlertStatus.CLOSED);
        assertThat(closed.payment().getStatus()).isEqualTo(PaymentStatus.DECLINED);
    }

    @Test
    void velocityRuleTriggersAfterLimitUsingRedis() {
        Account payer = newAccount("10000.00");
        Account payee = newAccount("0");

        List<Payment> results = new ArrayList<>();
        for (int i = 0; i < TEST_VELOCITY_MAX + 1; i++) {
            results.add(pay(payer, payee, "1.00"));
        }

        assertThat(results.subList(0, TEST_VELOCITY_MAX)).allSatisfy(p -> assertThat(p.getStatus()).isEqualTo(PaymentStatus.COMPLETED));
        Payment last = results.getLast();
        assertThat(last.getFraudRules()).contains(FraudRule.VELOCITY);
        assertThat(last.getStatus()).isEqualTo(PaymentStatus.PENDING_REVIEW);
        assertThat(balanceOf(payer)).isEqualByComparingTo("9980.00");
    }

    @Test
    void newBeneficiaryRuleClearsAfterFirstSettledPayment() {
        Account payer = newAccount("1000.00");
        Account payee = newAccount("0");

        Payment first = pay(payer, payee, "10.00");
        Payment second = pay(payer, payee, "10.00");

        assertThat(first.getFraudRules()).containsExactly(FraudRule.NEW_BENEFICIARY);
        assertThat(second.getFraudRules()).isEmpty();
        assertThat(second.getFraudDecision()).isEqualTo(FraudDecision.APPROVED);
    }

    @Test
    void onlyAnalystsCanChangeAlertStatusOverHttp() throws Exception {
        Account payer = newAccount("500000.00");
        Account payee = newAccount("0");
        Payment declined = pay(payer, payee, "150000.00");
        FraudAlert alert = alertRepository.findByPaymentId(declined.getId()).orElseThrow();
        String body = "{\"status\":\"IN_REVIEW\",\"note\":\"checking\"}";

        mvc.perform(patch("/api/v1/fraud/alerts/{id}/status", alert.getId())
                        .with(httpBasic("operator", "operator-dev-password"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        mvc.perform(patch("/api/v1/fraud/alerts/{id}/status", alert.getId())
                        .with(httpBasic("analyst", "analyst-dev-password"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_REVIEW"))
                .andExpect(jsonPath("$.reviewedBy").value("analyst"))
                .andExpect(jsonPath("$.allowedTransitions[0]").value("CLOSED"));

        mvc.perform(get("/api/v1/fraud/alerts").param("paymentId", declined.getId().toString())
                        .with(httpBasic("operator", "operator-dev-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].riskScore").value(70))
                .andExpect(jsonPath("$.content[0].payment.status").value("DECLINED"));
    }

    /** One small completed payment so the pair is no longer a "new beneficiary". */
    private void establishBeneficiary(Account payer, Account payee) {
        assertThat(pay(payer, payee, "10.00").getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    }
}
