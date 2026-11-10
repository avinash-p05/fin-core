package com.fincore.config;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints that need no sign-in. */
@RestController
@Tag(name = "Session")
public class PublicController {

    private final SecurityProperties props;

    public PublicController(SecurityProperties props) {
        this.props = props;
    }

    /** The public demo account is meant to be shared, so its credentials are returned as-is. */
    public record DemoAccountResponse(boolean enabled, String username, String password) {
    }

    @GetMapping("/api/v1/public/demo-account")
    @Operation(summary = "Public demo account for the 'Try the demo' button (OPERATOR role only)")
    public DemoAccountResponse demoAccount() {
        return props.demoEnabled()
                ? new DemoAccountResponse(true, props.demoUsername(), props.demoPassword())
                : new DemoAccountResponse(false, null, null);
    }
}
