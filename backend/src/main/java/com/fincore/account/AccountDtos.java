package com.fincore.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class AccountDtos {

    private AccountDtos() {
    }

    public record CreateAccountRequest(
            @NotNull UUID customerId,
            @NotBlank @Size(max = 200) String displayName,
            @DecimalMin(value = "0.00") @Digits(integer = 17, fraction = 2) BigDecimal openingBalance) {
    }

    public record AccountResponse(
            UUID id,
            UUID customerId,
            AccountType type,
            String displayName,
            String currency,
            BigDecimal balance,
            AccountStatus status,
            Instant createdAt,
            Instant updatedAt) {

        public static AccountResponse from(Account a) {
            return new AccountResponse(a.getId(), a.getCustomerId(), a.getType(), a.getDisplayName(),
                    a.getCurrency(), a.getBalance(), a.getStatus(), a.getCreatedAt(), a.getUpdatedAt());
        }
    }

    public record BalanceResponse(UUID accountId, String currency, BigDecimal balance, AccountStatus status, Instant asOf) {

        public static BalanceResponse from(Account a) {
            return new BalanceResponse(a.getId(), a.getCurrency(), a.getBalance(), a.getStatus(), Instant.now());
        }
    }
}
