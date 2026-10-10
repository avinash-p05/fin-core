package com.fincore.account;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.fincore.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@AutoConfigureMockMvc
class AccountApiTest extends IntegrationTest {

    private static final RequestPostProcessor OPERATOR = httpBasic("operator", "operator-dev-password");

    @Autowired
    MockMvc mvc;

    @Test
    void createsCustomerAndAccountAndReadsBalance() throws Exception {
        String email = "asha-" + UUID.randomUUID() + "@example.com";
        String customerJson = mvc.perform(post("/api/v1/customers").with(OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Asha Rao\",\"email\":\"" + email + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String customerId = JsonPath.read(customerJson, "$.id");

        String accountJson = mvc.perform(post("/api/v1/accounts").with(OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customerId + "\",\"displayName\":\"Primary\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        String accountId = JsonPath.read(accountJson, "$.id");

        mvc.perform(get("/api/v1/accounts/{id}/balance", accountId).with(OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(0.0));

        mvc.perform(post("/api/v1/accounts/{id}/freeze", accountId).with(OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));
        mvc.perform(post("/api/v1/accounts/{id}/unfreeze", accountId).with(OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void duplicateCustomerEmailIsConflict() throws Exception {
        String body = "{\"fullName\":\"Dup\",\"email\":\"dup-" + UUID.randomUUID() + "@example.com\"}";
        mvc.perform(post("/api/v1/customers").with(OPERATOR).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/customers").with(OPERATOR).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CUSTOMER_EXISTS"));
    }

    @Test
    void rejectsInvalidInputAndUnknownIds() throws Exception {
        mvc.perform(post("/api/v1/customers").with(OPERATOR).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"\",\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.email").exists());

        mvc.perform(get("/api/v1/accounts/{id}", UUID.randomUUID()).with(OPERATOR))
                .andExpect(status().isNotFound());
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/accounts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/accounts").with(httpBasic("operator", "wrong"))).andExpect(status().isUnauthorized());
    }

    @Test
    void systemAccountCannotBeFrozen() throws Exception {
        mvc.perform(post("/api/v1/accounts/{id}/freeze", Account.SYSTEM_FUNDING_ACCOUNT_ID).with(OPERATOR))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACCOUNT"));
    }
}
