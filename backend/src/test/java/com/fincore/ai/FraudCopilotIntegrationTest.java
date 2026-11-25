package com.fincore.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fincore.account.Account;
import com.fincore.fraud.FraudAlert;
import com.fincore.fraud.FraudAlertRepository;
import com.fincore.fraud.FraudAlertStatus;
import com.fincore.fraud.FraudReviewService;
import com.fincore.payment.Payment;
import com.fincore.payment.PaymentStatus;
import com.fincore.support.IntegrationTest;
import com.fincore.support.ScriptedChatModel;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Copilot end to end with real PostgreSQL: HTTP -> service -> Spring AI ChatClient -> @Tool
 * callbacks -> database, with a scripted model standing in for the LLM.
 */
@AutoConfigureMockMvc
@Import(FraudCopilotIntegrationTest.ModelConfig.class)
@TestPropertySource(properties = {
        "fincore.copilot.timeout=3s",
        "fincore.copilot.max-tool-calls=5",
        "fincore.copilot.quota.public-per-day=4",
        "fincore.copilot.quota.public-per-ip-per-day=2",
        "fincore.copilot.quota.analyst-per-day=50"})
class FraudCopilotIntegrationTest extends IntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class ModelConfig {
        @Bean
        ScriptedChatModel scriptedChatModel() {
            return new ScriptedChatModel();
        }
    }

    @Autowired
    ScriptedChatModel model;

    @Autowired
    MockMvc mvc;

    @Autowired
    FraudAlertRepository alertRepository;

    @Autowired
    FraudReviewService reviews;

    @Autowired
    MeterRegistry meters;

    @Autowired
    StringRedisTemplate redis;

    Account payer;
    Account payee;
    Payment earlier;
    Payment held;
    FraudAlert alert;

    @BeforeEach
    void heldPayment() {
        var keys = redis.keys("fincore:copilot:*"); // quota counters and cached answers from earlier tests
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        payer = newAccount("500000.00");
        payee = newAccount("0");
        earlier = pay(payer, payee, "2500.50");   // NEW_BENEFICIARY only (20) => approved, establishes payee
        held = pay(payer, payee, "120000.00");    // HIGH_VALUE (50) => REVIEW
        assertThat(held.getStatus()).isEqualTo(PaymentStatus.PENDING_REVIEW);
        alert = alertRepository.findByPaymentId(held.getId()).orElseThrow();
    }

    @Test
    void groundedAnswerUsesToolsAndChangesNothing() throws Exception {
        List<String> toolResults = new ArrayList<>();
        model.script(tools -> {
            assertThat(tools.names()).containsExactlyInAnyOrder(
                    "getPayment", "getRecentPayments", "getBeneficiaryHistory", "getAccountSummary");
            assertThat(tools.prompt.getContents()).contains(alert.getId().toString(), "120000.00", "untrusted");
            toolResults.add(tools.call("getRecentPayments", "{\"accountId\":\"%s\",\"days\":7}".formatted(payer.getId())));
            toolResults.add(tools.call("getBeneficiaryHistory", "{}"));
            toolResults.add(tools.call("getAccountSummary", "{\"accountId\":\"%s\"}".formatted(payer.getId())));
            return answer("Payment %s of INR 120000.00 is above the INR 100000.00 high-value threshold. The payer sent %s "
                            + "(INR 2500.50) to the same payee earlier, so the payee is known.".formatted(held.getId(), earlier.getId()),
                    "APPROVE", cite(held.getId(), "120000.00"), cite(earlier.getId(), "2500.50"));
        });
        var tokensBefore = meters.find("copilot.tokens").tag("type", "prompt").counter();
        double before = tokensBefore == null ? 0 : tokensBefore.count();

        copilot("analyst", "analyst-dev-password")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("GROUNDED"))
                .andExpect(jsonPath("$.suggestedAction").value("APPROVE"))
                .andExpect(jsonPath("$.confidence").value(0.8))
                .andExpect(jsonPath("$.citations.length()").value(2))
                .andExpect(jsonPath("$.toolCalls").value(3))
                .andExpect(jsonPath("$.model").value("scripted-test-model"))
                .andExpect(jsonPath("$.promptVersion").value(FraudCopilotService.PROMPT_VERSION))
                .andExpect(jsonPath("$.totalTokens").value(200))
                .andExpect(jsonPath("$.validationErrors.length()").value(0));

        assertThat(toolResults.get(0)).contains(held.getId().toString(), earlier.getId().toString());
        assertThat(toolResults.get(1)).contains("\"settledPayments\":1");
        assertThat(toolResults.get(2)).contains("497499.5"); // 500000.00 - 2500.50; held payment moved nothing

        // Advisory only: the alert and the held payment are exactly as before.
        assertThat(alertRepository.findById(alert.getId()).orElseThrow().getStatus()).isEqualTo(FraudAlertStatus.OPEN);
        assertThat(paymentService.get(held.getId()).getStatus()).isEqualTo(PaymentStatus.PENDING_REVIEW);
        assertThat(ledgerQueryService.forPayment(held.getId())).isEmpty();
        assertThat(meters.find("copilot.tokens").tag("type", "prompt").counter().count()).isEqualTo(before + 120);
    }

    @Test
    void hallucinatedPaymentIsDiscardedAndEscalated() throws Exception {
        UUID invented = UUID.randomUUID();
        model.script(tools -> answer("Customer paid %s (INR 120000.00) last week.".formatted(invented), "APPROVE",
                cite(invented, "120000.00")));

        copilot("analyst", "analyst-dev-password")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.suggestedAction").value("ESCALATE"))
                .andExpect(jsonPath("$.summary").doesNotExist())
                .andExpect(jsonPath("$.citations.length()").value(0))
                .andExpect(jsonPath("$.validationErrors[0]").value(org.hamcrest.Matchers.containsString("no tool returned")));
    }

    @Test
    void wrongAmountIsDiscarded() throws Exception {
        model.script(tools -> answer("Held payment of INR 12000.00.", "REJECT", cite(held.getId(), "12000.00")));

        copilot("analyst", "analyst-dev-password")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.suggestedAction").value("ESCALATE"));
    }

    @Test
    void unparseableAnswerIsDiscarded() throws Exception {
        model.script(tools -> "I think you should approve it.");

        copilot("analyst", "analyst-dev-password")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.suggestedAction").value("ESCALATE"));
    }

    @Test
    void toolsRefuseAccountsAndPaymentsOutsideTheAlert() throws Exception {
        Account stranger = newAccount("1000.00");
        Account strangerPayee = newAccount("0");
        Payment unrelated = pay(stranger, strangerPayee, "100.00");
        List<String> results = new ArrayList<>();
        model.script(tools -> {
            results.add(tools.call("getAccountSummary", "{\"accountId\":\"%s\"}".formatted(stranger.getId())));
            results.add(tools.call("getRecentPayments", "{\"accountId\":\"%s\",\"days\":30}".formatted(stranger.getId())));
            results.add(tools.call("getPayment", "{\"paymentId\":\"%s\"}".formatted(unrelated.getId())));
            return answer("Held payment of INR 120000.00.", "ESCALATE", cite(held.getId(), "120000.00"));
        });

        copilot("analyst", "analyst-dev-password")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("GROUNDED"));

        assertThat(results.get(0)).contains("not part of this alert").doesNotContain("1000");
        assertThat(results.get(1)).contains("not part of this alert");
        assertThat(results.get(2)).contains("outside this alert's scope");
    }

    @Test
    void exceedingTheToolBudgetAnswers503() throws Exception {
        model.script(tools -> {
            for (int i = 0; i < 7; i++) {
                tools.call("getBeneficiaryHistory", "{}");
            }
            return answer("Held payment of INR 120000.00.", "APPROVE", cite(held.getId(), "120000.00"));
        });

        copilot("analyst", "analyst-dev-password")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("COPILOT_UNAVAILABLE"));
    }

    @Test
    void modelErrorAnswers503WithNoSuggestion() throws Exception {
        model.script(tools -> {
            throw new IllegalStateException("provider down");
        });

        copilot("analyst", "analyst-dev-password")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("COPILOT_UNAVAILABLE"))
                .andExpect(jsonPath("$.suggestedAction").doesNotExist());
    }

    @Test
    void slowModelTimesOut() throws Exception {
        model.script(tools -> {
            Thread.sleep(6_000);
            return answer("late", "APPROVE");
        });

        copilot("analyst", "analyst-dev-password")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("COPILOT_TIMEOUT"));
    }

    @Test
    void repeatClicksReuseTheGroundedAnswer() throws Exception {
        model.script(tools -> answer("Held for review.", "ESCALATE"));

        copilot("analyst", "analyst-dev-password").andExpect(jsonPath("$.cached").value(false));
        copilot("analyst", "analyst-dev-password")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("GROUNDED"))
                .andExpect(jsonPath("$.cached").value(true));

        assertThat(model.calls()).isEqualTo(1);
    }

    @Test
    void failedAnswersAreNotCached() throws Exception {
        model.script(tools -> "not json");

        copilot("analyst", "analyst-dev-password").andExpect(jsonPath("$.outcome").value("VALIDATION_FAILED"));
        copilot("analyst", "analyst-dev-password").andExpect(jsonPath("$.cached").value(false));

        assertThat(model.calls()).isEqualTo(2);
    }

    @Test
    void demoUsersAreLimitedPerIp() throws Exception {
        model.script(tools -> answer("Held for review.", "ESCALATE"));

        copilot(newHeldAlert(), "demo", "demo-password", "203.0.113.1").andExpect(status().isOk());
        copilot(newHeldAlert(), "demo", "demo-password", "203.0.113.1").andExpect(status().isOk());
        copilot(newHeldAlert(), "demo", "demo-password", "203.0.113.1")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("COPILOT_QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("2 copilot calls")));
        // Another visitor still has their own allowance.
        copilot(newHeldAlert(), "demo", "demo-password", "203.0.113.2").andExpect(status().isOk());

        assertThat(model.calls()).isEqualTo(3); // the refused call never reached the model
    }

    @Test
    void sharedPublicPoolCapsAllVisitorsButNotAnalysts() throws Exception {
        model.script(tools -> answer("Held for review.", "ESCALATE"));
        for (String ip : List.of("198.51.100.1", "198.51.100.2")) {
            for (int i = 0; i < 2; i++) {
                copilot(newHeldAlert(), "operator", "operator-dev-password", ip).andExpect(status().isOk());
            }
        }

        copilot(newHeldAlert(), "demo", "demo-password", "198.51.100.3")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("demo's copilot allowance")));
        // The owner's analyst login has its own allowance and is not locked out by demo traffic.
        copilot(newHeldAlert(), "analyst", "analyst-dev-password", "198.51.100.3").andExpect(status().isOk());
    }

    @Test
    void cachedAnswersDoNotUseQuota() throws Exception {
        model.script(tools -> answer("Held for review.", "ESCALATE"));
        UUID same = alert.getId();

        for (int i = 0; i < 5; i++) {
            copilot(same, "demo", "demo-password", "192.0.2.9").andExpect(status().isOk());
        }

        assertThat(model.calls()).isEqualTo(1);
        copilot(newHeldAlert(), "demo", "demo-password", "192.0.2.9").andExpect(status().isOk()); // 2nd of 2
    }

    @Test
    void decidedAlertsAreNotReviewed() throws Exception {
        reviews.updateStatus(alert.getId(), FraudAlertStatus.REJECTED, "fraud", "analyst");

        copilot("analyst", "analyst-dev-password")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COPILOT_NOT_APPLICABLE"));
    }

    @Test
    void unknownAlertIs404() throws Exception {
        mvc.perform(post("/api/v1/fraud/alerts/{id}/copilot", UUID.randomUUID())
                        .with(httpBasic("analyst", "analyst-dev-password")))
                .andExpect(status().isNotFound());
    }

    private ResultActions copilot(String user, String password) throws Exception {
        return mvc.perform(post("/api/v1/fraud/alerts/{id}/copilot", alert.getId()).with(httpBasic(user, password)));
    }

    private ResultActions copilot(UUID alertId, String user, String password, String ip) throws Exception {
        return mvc.perform(post("/api/v1/fraud/alerts/{id}/copilot", alertId)
                .with(httpBasic(user, password))
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                }));
    }

    private UUID newHeldAlert() {
        Account from = newAccount("500000.00");
        Account to = newAccount("0");
        pay(from, to, "10.00");
        Payment p = pay(from, to, "120000.00");
        return alertRepository.findByPaymentId(p.getId()).orElseThrow().getId();
    }

    private static String cite(UUID id, String amount) {
        return "{\"paymentId\":\"%s\",\"amount\":%s}".formatted(id, amount);
    }

    private static String answer(String summary, String action, String... citations) {
        return """
                {"summary": "%s", "riskFactors": ["High value"], "suggestedAction": "%s", "confidence": 0.8,
                 "citations": [%s]}
                """.formatted(summary, action, String.join(",", citations));
    }
}
