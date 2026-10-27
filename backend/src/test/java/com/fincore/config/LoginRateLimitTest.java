package com.fincore.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.ThreadLocalRandom;

import com.fincore.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Failed sign-ins are counted per client IP in real Redis. */
@AutoConfigureMockMvc
class LoginRateLimitTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    SecurityProperties props;

    @Test
    void ipIsBlockedAfterTooManyFailedSignIns() throws Exception {
        RequestPostProcessor ip = fromIp(randomIp());

        for (int i = 0; i < props.loginMaxFailures(); i++) {
            mvc.perform(get("/api/v1/me").with(ip).with(httpBasic("analyst", "wrong-" + i)))
                    .andExpect(status().isUnauthorized());
        }

        // Even the correct password is refused now: the password is not checked at all.
        mvc.perform(get("/api/v1/me").with(ip).with(httpBasic("analyst", "analyst-dev-password")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("TOO_MANY_LOGIN_ATTEMPTS"));
    }

    @Test
    void otherIpsAndSuccessfulSignInsAreNotAffected() throws Exception {
        RequestPostProcessor attacker = fromIp(randomIp());
        for (int i = 0; i < props.loginMaxFailures(); i++) {
            mvc.perform(get("/api/v1/me").with(attacker).with(httpBasic("analyst", "wrong")));
        }

        RequestPostProcessor someoneElse = fromIp(randomIp());
        mvc.perform(get("/api/v1/me").with(someoneElse).with(httpBasic("analyst", "analyst-dev-password")))
                .andExpect(status().isOk());
        // Successful requests are never counted, however many there are.
        for (int i = 0; i < props.loginMaxFailures() + 5; i++) {
            mvc.perform(get("/api/v1/me").with(someoneElse).with(httpBasic("analyst", "analyst-dev-password")))
                    .andExpect(status().isOk());
        }
    }

    private static RequestPostProcessor fromIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private static String randomIp() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "203.0." + r.nextInt(256) + "." + r.nextInt(1, 255);
    }
}
