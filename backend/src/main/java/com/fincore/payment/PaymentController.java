package com.fincore.payment;

import java.util.List;
import java.util.UUID;

import com.fincore.common.PageResponse;
import com.fincore.payment.PaymentDtos.CreatePaymentRequest;
import com.fincore.payment.PaymentDtos.PaymentResponse;
import com.fincore.payment.PaymentDtos.PaymentResult;
import com.fincore.payment.PaymentDtos.RefundRequest;
import com.fincore.payment.PaymentDtos.RefundResponse;
import com.fincore.payment.PaymentDtos.RefundResult;
import com.fincore.payment.PaymentService.PaymentFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
@Validated
@Tag(name = "Payments")
public class PaymentController {

    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final PaymentService service;
    private final RefundService refunds;

    public PaymentController(PaymentService service, RefundService refunds) {
        this.service = service;
        this.refunds = refunds;
    }

    @PostMapping
    @Operation(summary = "Create an INR transfer",
            description = "Returns 201 for a new payment and 200 with Idempotent-Replayed: true when the same "
                    + "Idempotency-Key and payload were seen before. The business outcome is in 'status'.")
    public ResponseEntity<PaymentResponse> create(
            @Parameter(description = "Client-generated unique key, 8-100 chars") @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {
        PaymentResult result = service.create(idempotencyKey, request);
        return respond(result.payment(), result.replayed());
    }

    @GetMapping
    @Operation(summary = "Payment history, newest first")
    public PageResponse<PaymentResponse> list(
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")));
        return PageResponse.of(service.list(new PaymentFilter(accountId, status), pageable), PaymentResponse::from);
    }

    @GetMapping("/{paymentId}")
    @Operation(summary = "Get a payment and its current status")
    public PaymentResponse get(@PathVariable UUID paymentId) {
        return PaymentResponse.from(service.get(paymentId));
    }

    @PostMapping("/{paymentId}/refund")
    @Operation(summary = "Refund a completed payment (full remaining amount if 'amount' is omitted)",
            description = "Posts a compensating ledger transaction. Requires an Idempotency-Key.")
    public ResponseEntity<RefundResponse> refund(
            @PathVariable UUID paymentId,
            @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
            @Valid @RequestBody(required = false) RefundRequest request) {
        RefundResult result = refunds.refund(paymentId, idempotencyKey,
                request == null ? new RefundRequest(null, null) : request);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header(REPLAYED_HEADER, String.valueOf(result.replayed()))
                .body(RefundResponse.from(result.refund(), result.payment()));
    }

    @GetMapping("/{paymentId}/refunds")
    @Operation(summary = "Refunds of a payment")
    public List<RefundResponse> refunds(@PathVariable UUID paymentId) {
        Payment payment = service.get(paymentId);
        return refunds.forPayment(paymentId).stream().map(r -> RefundResponse.from(r, payment)).toList();
    }

    static ResponseEntity<PaymentResponse> respond(Payment payment, boolean replayed) {
        return ResponseEntity.status(replayed ? HttpStatus.OK : HttpStatus.CREATED)
                .header(REPLAYED_HEADER, String.valueOf(replayed))
                .body(PaymentResponse.from(payment));
    }
}
