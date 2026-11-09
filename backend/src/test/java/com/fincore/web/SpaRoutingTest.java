package com.fincore.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fincore.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** The backend serves the React dashboard: public static files, SPA deep links, protected API. */
@AutoConfigureMockMvc
class SpaRoutingTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void clientSideRoutesForwardToIndexWithoutSignIn() throws Exception {
        for (String route : new String[] {"/dashboard", "/accounts", "/payments/new", "/fraud"}) {
            mvc.perform(get(route)).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        }
    }

    @Test
    void staticFilesArePublicAndNotForwarded() throws Exception {
        // Not on the test classpath, so 404; the point is: no 401 and no forward to index.html.
        mvc.perform(get("/assets/app-1234.js")).andExpect(status().isNotFound()).andExpect(forwardedUrl(null));
        mvc.perform(get("/favicon.ico")).andExpect(status().isNotFound());
    }

    @Test
    void apiStillRequiresSignInAndUnknownApiPathsAreNotTheApp() throws Exception {
        mvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"));
        mvc.perform(get("/api/v1/no-such-endpoint").with(httpBasic("operator", "operator-dev-password")))
                .andExpect(status().isNotFound())
                .andExpect(forwardedUrl(null));
    }

    @Test
    void securityHeadersAreSent() throws Exception {
        mvc.perform(get("/dashboard"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
    }
}
