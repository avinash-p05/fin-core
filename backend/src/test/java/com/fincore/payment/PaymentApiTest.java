package com.fincore.payment;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fincore.account.Account;
import com.fincore.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@AutoConfigureMockMvc
class PaymentApiTest extends IntegrationTest {

    private static final RequestPostProcessor OPERATOR = httpBasic("operator", "operator-dev-password");

    @Autowired
    MockMvc mvc;

    @Test
    void createReplayAndConflictOverHttp() throws Exception {
        Account alice = newAccount("1000.00");
        Account bob = newAccount("0");
        String key = newKey();
        String body = body(alice, bob, "125.50");

        String id = com.jayway.jsonpath.JsonPath.read(mvc.perform(post("/api/v1/payments").with(OPERATOR)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andReturn().getResponse().getContentAsString(), "$.id");

        mvc.perform(post("/api/v1/payments").with(OPERATOR)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(id));

        mvc.perform(post("/api/v1/payments").with(OPERATOR)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body(alice, bob, "1.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        mvc.perform(get("/api/v1/payments/{id}", id).with(OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(125.50));

        mvc.perform(get("/api/v1/payments/{id}/ledger", id).with(OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].balanced").value(true))
                .andExpect(jsonPath("$[0].entries.length()").value(2));

        mvc.perform(get("/api/v1/accounts/{id}/balance/verification", alice.getId()).with(OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALID"))
                .andExpect(jsonPath("$.ledgerBalance").value(874.50));

        mvc.perform(get("/api/v1/payments").param("accountId", bob.getId().toString()).with(OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mvc.perform(get("/api/v1/dashboard/summary").with(OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPayments").isNumber())
                .andExpect(jsonPath("$.recentPayments").isArray());

        mvc.perform(get("/api/v1/ledger/entries").param("paymentId", id).with(OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void missingIdempotencyKeyAndInvalidAmountAreBadRequests() throws Exception {
        Account alice = newAccount("10.00");
        Account bob = newAccount("0");

        mvc.perform(post("/api/v1/payments").with(OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON).content(body(alice, bob, "1.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));

        mvc.perform(post("/api/v1/payments").with(OPERATOR).header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON).content(body(alice, bob, "-5")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private static String body(Account from, Account to, String amount) {
        return """
                {"sourceAccountId":"%s","destinationAccountId":"%s","amount":%s,"description":"rent"}
                """.formatted(from.getId(), to.getId(), amount);
    }
}
