package com.fincore.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fincore.common.ApiException;
import com.fincore.common.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A customer deposit account (or the system funding account).
 *
 * <p>{@code balance} is a materialised running total that is only changed together with posted
 * ledger entries in the same database transaction; ledger history is the audit source used for
 * reconciliation.
 */
@Entity
@Table(name = "accounts")
public class Account {

    /** Counterparty for opening balances. Created by the V1 migration. */
    public static final UUID SYSTEM_FUNDING_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Id
    private UUID id;

    @Column(name = "customer_id", updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, updatable = false)
    private AccountType type;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Account() {
    }

    public static Account openCustomerAccount(UUID customerId, String displayName) {
        Account a = new Account();
        a.id = UUID.randomUUID();
        a.customerId = customerId;
        a.type = AccountType.CUSTOMER;
        a.displayName = displayName;
        a.currency = Money.CURRENCY;
        a.balance = Money.zero();
        a.status = AccountStatus.ACTIVE;
        a.createdAt = Instant.now();
        a.updatedAt = a.createdAt;
        return a;
    }

    /** Callers must hold a row lock on this account. */
    public void debit(BigDecimal amount) {
        BigDecimal next = balance.subtract(amount);
        if (type == AccountType.CUSTOMER && next.signum() < 0) {
            throw ApiException.unprocessable("INSUFFICIENT_FUNDS", "Account " + id + " has insufficient funds");
        }
        balance = next;
        updatedAt = Instant.now();
    }

    /** Callers must hold a row lock on this account. */
    public void credit(BigDecimal amount) {
        balance = balance.add(amount);
        updatedAt = Instant.now();
    }

    public boolean hasAvailable(BigDecimal amount) {
        return type == AccountType.SYSTEM || balance.compareTo(amount) >= 0;
    }

    public void freeze() {
        status = AccountStatus.FROZEN;
        updatedAt = Instant.now();
    }

    public void unfreeze() {
        status = AccountStatus.ACTIVE;
        updatedAt = Instant.now();
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public AccountType getType() {
        return type;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
