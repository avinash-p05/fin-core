package com.fincore.fraud;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fincore.common.PageResponse;
import com.fincore.fraud.FraudAlertService.AlertFilter;
import com.fincore.payment.Payment;
import com.fincore.payment.PaymentService;
import com.fincore.payment.PaymentStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/fraud/alerts")
@Validated
@Tag(name = "Fraud")
public class FraudController {

    private final FraudAlertService alerts;
    private final FraudReviewService reviews;
    private final PaymentService payments;

    public FraudController(FraudAlertService alerts, FraudReviewService reviews, PaymentService payments) {
        this.alerts = alerts;
        this.reviews = reviews;
        this.payments = payments;
    }

    public record UpdateAlertStatusRequest(@NotNull FraudAlertStatus status, @Size(max = 1000) String note) {
    }

    public record AlertPayment(UUID id, UUID sourceAccountId, UUID destinationAccountId, BigDecimal amount,
                               PaymentStatus status, String failureReason) {
    }

    public record FraudAlertResponse(
            UUID id,
            UUID paymentId,
            int riskScore,
            FraudDecision decision,
            List<FraudRule> triggeredRules,
            FraudAlertStatus status,
            Set<FraudAlertStatus> allowedTransitions,
            String reviewedBy,
            String reviewNote,
            Instant createdAt,
            Instant updatedAt,
            AlertPayment payment) {

        static FraudAlertResponse from(FraudAlert a, Payment p) {
            return new FraudAlertResponse(a.getId(), a.getPaymentId(), a.getRiskScore(), a.getDecision(),
                    a.getTriggeredRules(), a.getStatus(), a.allowedTransitions(), a.getReviewedBy(), a.getReviewNote(),
                    a.getCreatedAt(), a.getUpdatedAt(),
                    new AlertPayment(p.getId(), p.getSourceAccountId(), p.getDestinationAccountId(), p.getAmount(),
                            p.getStatus(), p.getFailureReason()));
        }
    }

    @GetMapping
    @Operation(summary = "List fraud alerts, newest first")
    public PageResponse<FraudAlertResponse> list(
            @RequestParam(required = false) FraudAlertStatus status,
            @RequestParam(required = false) FraudDecision decision,
            @RequestParam(required = false) UUID paymentId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")));
        return PageResponse.of(alerts.list(new AlertFilter(status, decision, paymentId), pageable),
                a -> FraudAlertResponse.from(a, payments.get(a.getPaymentId())));
    }

    @GetMapping("/{alertId}")
    @Operation(summary = "Get a fraud alert")
    public FraudAlertResponse get(@PathVariable UUID alertId) {
        FraudAlert alert = alerts.get(alertId);
        return FraudAlertResponse.from(alert, payments.get(alert.getPaymentId()));
    }

    @PatchMapping("/{alertId}/status")
    @Operation(summary = "Review decision (ANALYST role). APPROVED executes a held payment; REJECTED declines it.")
    public FraudAlertResponse updateStatus(@PathVariable UUID alertId,
                                           @Valid @RequestBody UpdateAlertStatusRequest request,
                                           Principal principal) {
        var outcome = reviews.updateStatus(alertId, request.status(), request.note(), principal.getName());
        return FraudAlertResponse.from(outcome.alert(), outcome.payment());
    }
}
