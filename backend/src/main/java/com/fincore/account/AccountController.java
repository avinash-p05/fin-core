package com.fincore.account;

import java.util.List;
import java.util.UUID;

import com.fincore.account.AccountDtos.AccountResponse;
import com.fincore.account.AccountDtos.BalanceResponse;
import com.fincore.account.AccountDtos.CreateAccountRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts")
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Open an INR account for a customer")
    public AccountResponse create(@Valid @RequestBody CreateAccountRequest request) {
        return AccountResponse.from(service.create(request));
    }

    @GetMapping
    @Operation(summary = "List customer accounts, optionally filtered by customer")
    public List<AccountResponse> list(@RequestParam(required = false) UUID customerId) {
        return service.list(customerId).stream().map(AccountResponse::from).toList();
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "Get an account")
    public AccountResponse get(@PathVariable UUID accountId) {
        return AccountResponse.from(service.get(accountId));
    }

    @GetMapping("/{accountId}/balance")
    @Operation(summary = "Get the current balance of an account")
    public BalanceResponse balance(@PathVariable UUID accountId) {
        return BalanceResponse.from(service.get(accountId));
    }

    @PostMapping("/{accountId}/freeze")
    @Operation(summary = "Freeze an account: it can neither send nor receive transfers")
    public AccountResponse freeze(@PathVariable UUID accountId) {
        return AccountResponse.from(service.freeze(accountId));
    }

    @PostMapping("/{accountId}/unfreeze")
    @Operation(summary = "Unfreeze an account")
    public AccountResponse unfreeze(@PathVariable UUID accountId) {
        return AccountResponse.from(service.unfreeze(accountId));
    }
}
