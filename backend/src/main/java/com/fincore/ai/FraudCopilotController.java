package com.fincore.ai;

import java.util.UUID;

import com.fincore.ai.CopilotDtos.CopilotResponse;
import com.fincore.config.SecurityConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/fraud/alerts")
@Tag(name = "Fraud")
public class FraudCopilotController {

    private final FraudCopilotService copilot;

    public FraudCopilotController(FraudCopilotService copilot) {
        this.copilot = copilot;
    }

    @PostMapping("/{alertId}/copilot")
    @Operation(summary = "Draft an AI case summary for a held payment. Advisory only: changes nothing; answers that "
            + "fail grounding validation come back as ESCALATE with no summary. Daily limits apply (429 when used up).")
    public CopilotResponse review(@PathVariable UUID alertId, Authentication auth, HttpServletRequest request) {
        boolean analyst = auth.getAuthorities().stream()
                .anyMatch(a -> ("ROLE_" + SecurityConfig.ROLE_ANALYST).equals(a.getAuthority()));
        return copilot.review(alertId, new CopilotQuota.Caller(auth.getName(), analyst, request.getRemoteAddr()));
    }
}
