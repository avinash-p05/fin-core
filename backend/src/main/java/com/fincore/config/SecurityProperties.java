package com.fincore.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
        List<String> allowedOrigins) {
}
