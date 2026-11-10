package com.fincore.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.fincore.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The public demo account: discoverable without sign-in, operator access only. */
@AutoConfigureMockMvc
class DemoAccountTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void demoCredentialsArePublic() throws Exception {
        mvc.perform(get("/api/v1/public/demo-account"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.username").value("demo"))
                .andExpect(jsonPath("$.password").value("demo-password"));
    }

    @Test
    void demoUserIsAnOperatorButCannotReviewFraudAlerts() throws Exception {
        mvc.perform(get("/api/v1/me").with(httpBasic("demo", "demo-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.roles[0]").value("OPERATOR"));
        mvc.perform(get("/api/v1/accounts").with(httpBasic("demo", "demo-password")))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/v1/fraud/alerts/{id}/status", UUID.randomUUID()).with(httpBasic("demo", "demo-password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isForbidden());
    }
}
