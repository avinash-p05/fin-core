package com.fincore.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Local-development credentials. These are NOT production authentication: there is no user
 * store, no rotation, no MFA, and HTTP Basic sends credentials on every request.
 */
@ConfigurationProperties(prefix = "fincore.security")
public record SecurityProperties(
        String operatorUsername,
        String operatorPassword,
        String analystUsername,
        String analystPassword,
        List<String> allowedOrigins,
        // BCrypt cost factor (2^n rounds). HTTP Basic re-verifies the password on EVERY request, so this
        // dominates request latency; the load-test profile lowers it to isolate the payment path.
        @DefaultValue("10") int bcryptStrength,
        // Failed sign-ins allowed per client IP within the window before further attempts get HTTP 429.
        @DefaultValue("10") int loginMaxFailures,
        @DefaultValue("15m") Duration loginFailureWindow) {
}
