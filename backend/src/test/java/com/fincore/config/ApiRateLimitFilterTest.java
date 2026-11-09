package com.fincore.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fincore.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Per-IP /api request limit, counted in the real Redis container. */
class ApiRateLimitFilterTest extends IntegrationTest {

    @Autowired
    StringRedisTemplate redis;

    @Test
    void requestsAboveTheLimitGet429AndOtherIpsAreUnaffected() throws Exception {
        ApiRateLimitFilter filter = new ApiRateLimitFilter(redis, 5);
        // Start right after a second boundary so all 8 calls fall into the same one-second window.
        Thread.sleep(1000 - System.currentTimeMillis() % 1000 + 20);

        int ok = 0;
        int limited = 0;
        for (int i = 0; i < 8; i++) {
            MockHttpServletResponse response = call(filter, "/api/v1/accounts", "198.51.100.7");
            if (response.getStatus() == 429) {
                limited++;
                assertThat(response.getHeader("Retry-After")).isEqualTo("1");
                assertThat(response.getContentAsString()).contains("RATE_LIMITED");
            } else {
                ok++;
            }
        }
        assertThat(ok).isEqualTo(5);
        assertThat(limited).isEqualTo(3);

        assertThat(call(filter, "/api/v1/accounts", "198.51.100.8").getStatus()).isEqualTo(200);
    }

    @Test
    void nonApiPathsAreNotLimited() throws Exception {
        ApiRateLimitFilter filter = new ApiRateLimitFilter(redis, 1);
        for (int i = 0; i < 5; i++) {
            assertThat(call(filter, "/assets/app.js", "198.51.100.9").getStatus()).isEqualTo(200);
        }
    }

    private static MockHttpServletResponse call(ApiRateLimitFilter filter, String uri, String ip) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
