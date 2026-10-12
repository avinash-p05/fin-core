package com.fincore.ledger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fincore.common.PageResponse;
import com.fincore.ledger.LedgerDtos.BalanceVerificationResponse;
import com.fincore.ledger.LedgerDtos.LedgerEntryResponse;
import com.fincore.ledger.LedgerDtos.LedgerTransactionResponse;
import com.fincore.ledger.LedgerDtos.LedgerVerificationResponse;
import com.fincore.ledger.LedgerQueryService.EntryFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "Ledger")
public class LedgerController {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));

    private final LedgerQueryService ledger;

    public LedgerController(LedgerQueryService ledger) {
        this.ledger = ledger;
    }

    @GetMapping("/ledger/entries")
    @Operation(summary = "Search ledger entries")
    public PageResponse<LedgerEntryResponse> entries(
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) UUID paymentId,
            @RequestParam(required = false) EntryDirection direction,
            @RequestParam(required = false) LedgerTransactionType transactionType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        var filter = new EntryFilter(accountId, paymentId, direction, transactionType, from, to);
        var result = ledger.search(filter, PageRequest.of(page, size, NEWEST_FIRST));
        return PageResponse.of(result, e -> e);
    }

    @GetMapping("/ledger/verification")
    @Operation(summary = "Verify the whole ledger: balanced transactions and reconciled balances")
    public LedgerVerificationResponse verifyLedger() {
        return ledger.verifyLedger();
    }

    @GetMapping("/accounts/{accountId}/ledger")
    @Operation(summary = "Ledger history of one account")
    public PageResponse<LedgerEntryResponse> accountLedger(
            @PathVariable UUID accountId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        var filter = new EntryFilter(accountId, null, null, null, null, null);
        return PageResponse.of(ledger.search(filter, PageRequest.of(page, size, NEWEST_FIRST)), e -> e);
    }

    @GetMapping("/accounts/{accountId}/balance/verification")
    @Operation(summary = "Reconcile the stored balance against ledger history")
    public BalanceVerificationResponse verifyBalance(@PathVariable UUID accountId) {
        return ledger.verifyAccount(accountId);
    }

    @GetMapping("/payments/{paymentId}/ledger")
    @Operation(summary = "Ledger transactions (transfer and refunds) posted for a payment")
    public List<LedgerTransactionResponse> paymentLedger(@PathVariable UUID paymentId) {
        return ledger.forPayment(paymentId);
    }
}
