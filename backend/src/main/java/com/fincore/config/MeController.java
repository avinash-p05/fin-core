package com.fincore.config;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lets the dashboard verify credentials and decide which actions to enable. */
@RestController
@Tag(name = "Session")
public class MeController {

    public record MeResponse(String username, List<String> roles) {
    }

    @GetMapping("/api/v1/me")
    @Operation(summary = "Current user and roles")
    public MeResponse me(Authentication authentication) {
        return new MeResponse(authentication.getName(), authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                // Spring Security 7 also grants factor authorities (e.g. FACTOR_PASSWORD); expose roles only.
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .sorted()
                .toList());
    }
}
