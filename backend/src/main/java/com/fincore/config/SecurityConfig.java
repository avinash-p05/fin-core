package com.fincore.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless HTTP Basic with two in-memory roles:
 * <ul>
 *   <li>OPERATOR: accounts, payments, refunds, ledger, read-only fraud views.</li>
 *   <li>ANALYST: everything an operator can do, plus fraud-alert review decisions.</li>
 * </ul>
 * This is a local-development setup, documented as such in the README.
 */
@Configuration
public class SecurityConfig {

    public static final String ROLE_OPERATOR = "OPERATOR";
    public static final String ROLE_ANALYST = "ANALYST";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, LoginAttemptLimiter loginLimiter,
                                           StringRedisTemplate redis, SecurityProperties props) throws Exception {
        JsonSecurityErrorHandler errors = new JsonSecurityErrorHandler();
        http
                // Stateless API authenticated by an Authorization header on every request; no cookies, so no CSRF surface.
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/public/**").permitAll()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/fraud/alerts/**").hasRole(ROLE_ANALYST)
                        .requestMatchers("/api/**").hasAnyRole(ROLE_OPERATOR, ROLE_ANALYST)
                        .requestMatchers("/actuator/**").authenticated()
                        // Everything else is the React dashboard: index.html, /assets/**, favicon and
                        // client-side routes such as /payments/new. Public; the data behind it is not.
                        .requestMatchers(HttpMethod.GET, "/**").permitAll()
                        .anyRequest().authenticated())
                .headers(headers -> headers
                        // Spring Security already sends X-Content-Type-Options, X-Frame-Options: DENY,
                        // HSTS (on HTTPS requests) and no-store Cache-Control for API responses.
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
                // JSON 401/403 without "WWW-Authenticate: Basic", so browsers never pop up their own login dialog.
                .httpBasic(basic -> basic.authenticationEntryPoint(errors))
                .exceptionHandling(e -> e.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                // Brute-force protection: blocks an IP after repeated failed sign-ins (see LoginRateLimitFilter).
                .addFilterBefore(new LoginRateLimitFilter(loginLimiter), BasicAuthenticationFilter.class)
                // Per-IP request limit for all /api calls (see ApiRateLimitFilter); runs before the login check.
                .addFilterBefore(new ApiRateLimitFilter(redis, props.apiRequestsPerSecond()), LoginRateLimitFilter.class);
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder(SecurityProperties props) {
        return new BCryptPasswordEncoder(props.bcryptStrength());
    }

    @Bean
    UserDetailsService userDetailsService(SecurityProperties props, PasswordEncoder encoder) {
        InMemoryUserDetailsManager users = new InMemoryUserDetailsManager(
                User.withUsername(props.operatorUsername())
                        .password(encoder.encode(props.operatorPassword()))
                        .roles(ROLE_OPERATOR)
                        .build(),
                User.withUsername(props.analystUsername())
                        .password(encoder.encode(props.analystPassword()))
                        .roles(ROLE_OPERATOR, ROLE_ANALYST)
                        .build());
        if (props.demoEnabled()) {
            // Least privilege for the public account: payments and ledger, but no fraud review decisions.
            users.createUser(User.withUsername(props.demoUsername())
                    .password(encoder.encode(props.demoPassword()))
                    .roles(ROLE_OPERATOR)
                    .build());
        }
        return users;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(SecurityProperties props) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(props.allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        cors.setExposedHeaders(List.of("Idempotent-Replayed"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
