package com.fincore.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.fincore.account.Account;
import com.fincore.ledger.LedgerDtos.VerificationStatus;
import com.fincore.payment.Payment;
import com.fincore.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

class LedgerIntegrityTest extends IntegrationTest {

    @Autowired
    TransactionTemplate tx;

    @Test
    void failureAfterTransferRollsBackBalancesLedgerPaymentAndIdempotencyKey() {
        Account alice = newAccount("1000.00");
        Account bob = newAccount("0");
        String key = newKey();
        UUID[] paymentId = new UUID[1];

        // The payment joins the outer transaction (REQUIRED propagation). A failure after the
        // transfer was posted must undo every write: balances, ledger, payment, idempotency key.
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            paymentId[0] = pay(key, alice, bob, "300.00").payment().getId();
            throw new IllegalStateException("simulated crash after posting");
        })).hasMessageContaining("simulated crash");

        assertThat(balanceOf(alice)).isEqualByComparingTo("1000.00");
        assertThat(balanceOf(bob)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE id = ?", Integer.class, paymentId[0])).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_transactions WHERE payment_id = ?", Integer.class, paymentId[0])).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ?", Integer.class, key)).isZero();

        // The key is free again, so a retry executes exactly once.
        Payment retried = pay(key, alice, bob, "300.00").payment();
        assertThat(balanceOf(alice)).isEqualByComparingTo("700.00");
        assertThat(retried.getId()).isNotEqualTo(paymentId[0]);
    }

    @Test
    void databaseRejectsUnbalancedLedgerTransactionsAtCommit() {
        Account alice = newAccount("10.00");
        UUID txId = UUID.randomUUID();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO ledger_transactions (id, transaction_type, description) VALUES (?, 'TRANSFER', 'bad')", txId);
            jdbc.update("INSERT INTO ledger_entries (id, ledger_transaction_id, account_id, direction, amount) VALUES (?, ?, ?, 'DEBIT', 5.00)",
                    UUID.randomUUID(), txId, alice.getId());
            jdbc.update("INSERT INTO ledger_entries (id, ledger_transaction_id, account_id, direction, amount) VALUES (?, ?, ?, 'CREDIT', 4.00)",
                    UUID.randomUUID(), txId, Account.SYSTEM_FUNDING_ACCOUNT_ID);
        })).hasStackTraceContaining("unbalanced ledger transaction");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_transactions WHERE id = ?", Integer.class, txId)).isZero();
    }

    @Test
    void postedLedgerEntriesAreImmutable() {
        Account alice = newAccount("10.00");
        UUID entryId = jdbc.queryForObject("SELECT id FROM ledger_entries WHERE account_id = ?", UUID.class, alice.getId());

        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_entries SET amount = 1000 WHERE id = ?", entryId))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("ledger rows are immutable");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entries WHERE id = ?", entryId))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("ledger rows are immutable");
    }

    @Test
    void verificationDetectsBalanceThatDisagreesWithLedger() {
        Account alice = newAccount("100.00");
        // Simulate corruption that bypasses the application (the ledger itself is untouched).
        jdbc.update("UPDATE accounts SET balance = 150.00 WHERE id = ?", alice.getId());
        try {
            var result = ledgerQueryService.verifyAccount(alice.getId());
            assertThat(result.status()).isEqualTo(VerificationStatus.INVALID);
            assertThat(result.difference()).isEqualByComparingTo("50.00");
            var global = ledgerQueryService.verifyLedger();
            assertThat(global.status()).isEqualTo(VerificationStatus.INVALID);
            assertThat(global.accountMismatches()).anySatisfy(m -> assertThat(m.accountId()).isEqualTo(alice.getId()));
        } finally {
            jdbc.update("UPDATE accounts SET balance = 100.00 WHERE id = ?", alice.getId());
        }
        assertThat(ledgerQueryService.verifyLedger().status()).isEqualTo(VerificationStatus.VALID);
    }

    @Test
    void applicationGuardRejectsUnbalancedLines() {
        assertThatThrownBy(() -> LedgerService.validateBalanced(java.util.List.of(
                new LedgerService.Line(UUID.randomUUID(), EntryDirection.DEBIT, new java.math.BigDecimal("1.00")),
                new LedgerService.Line(UUID.randomUUID(), EntryDirection.CREDIT, new java.math.BigDecimal("0.99")))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
