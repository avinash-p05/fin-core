package com.fincore.ai;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.fincore.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Default configuration (spring.ai.model.chat=none): the app starts and the copilot says it is off. */
@AutoConfigureMockMvc
class CopilotDisabledTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void answers503WhenNoModelIsConfigured() throws Exception {
        mvc.perform(post("/api/v1/fraud/alerts/{id}/copilot", UUID.randomUUID())
                        .with(httpBasic("analyst", "analyst-dev-password")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("COPILOT_DISABLED"));
    }
}
