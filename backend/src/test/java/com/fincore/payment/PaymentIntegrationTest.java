package com.fincore.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.fincore.account.Account;
import com.fincore.common.ApiException;
import com.fincore.ledger.EntryDirection;
import com.fincore.ledger.LedgerDtos.LedgerTransactionResponse;
import com.fincore.ledger.LedgerDtos.VerificationStatus;
import com.fincore.ledger.LedgerTransactionType;
import com.fincore.payment.PaymentDtos.PaymentResult;
import com.fincore.support.IntegrationTest;
import org.junit.jupiter.api.Test;

class PaymentIntegrationTest extends IntegrationTest {

    @Test
    void completedTransferMovesMoneyAndPostsBalancedLedger() {
        Account alice = newAccount("1000.00");
        Account bob = newAccount("50.00");

        Payment payment = pay(alice, bob, "250.75");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(balanceOf(alice)).isEqualByComparingTo("749.25");
        assertThat(balanceOf(bob)).isEqualByComparingTo("300.75");

        List<LedgerTransactionResponse> ledger = ledgerQueryService.forPayment(payment.getId());
        assertThat(ledger).hasSize(1);
        LedgerTransactionResponse tx = ledger.getFirst();
        assertThat(tx.type()).isEqualTo(LedgerTransactionType.TRANSFER);
        assertThat(tx.balanced()).isTrue();
        assertThat(tx.totalDebits()).isEqualByComparingTo("250.75");
        assertThat(tx.entries()).anySatisfy(e -> {
            assertThat(e.accountId()).isEqualTo(alice.getId());
            assertThat(e.direction()).isEqualTo(EntryDirection.DEBIT);
        }).anySatisfy(e -> {
            assertThat(e.accountId()).isEqualTo(bob.getId());
            assertThat(e.direction()).isEqualTo(EntryDirection.CREDIT);
        });

        assertThat(ledgerQueryService.verifyAccount(alice.getId()).status()).isEqualTo(VerificationStatus.VALID);
        assertThat(ledgerQueryService.verifyAccount(bob.getId()).status()).isEqualTo(VerificationStatus.VALID);
        assertThat(ledgerQueryService.verifyLedger().status()).isEqualTo(VerificationStatus.VALID);
    }

    @Test
    void openingBalanceIsAnExplicitLedgerTransaction() {
        Account account = newAccount("500.00");

        var verification = ledgerQueryService.verifyAccount(account.getId());
        assertThat(verification.status()).isEqualTo(VerificationStatus.VALID);
        assertThat(verification.ledgerBalance()).isEqualByComparingTo("500.00");
        assertThat(verification.entryCount()).isEqualTo(1);
        Integer openings = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ledger_entries e JOIN ledger_transactions t ON t.id = e.ledger_transaction_id
                 WHERE e.account_id = ? AND t.transaction_type = 'OPENING_BALANCE' AND e.direction = 'CREDIT'
                """, Integer.class, account.getId());
        assertThat(openings).isEqualTo(1);
    }

    @Test
    void insufficientFundsFailsWithoutMovingMoney() {
        Account alice = newAccount("100.00");
        Account bob = newAccount("0");

        Payment payment = pay(alice, bob, "100.01");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(balanceOf(alice)).isEqualByComparingTo("100.00");
        assertThat(balanceOf(bob)).isEqualByComparingTo("0");
        assertThat(ledgerQueryService.forPayment(payment.getId())).isEmpty();
    }

    @Test
    void frozenAccountsCannotSendOrReceive() {
        Account alice = newAccount("100.00");
        Account bob = newAccount("0");
        accountService.freeze(bob.getId());

        Payment payment = pay(alice, bob, "10.00");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("ACCOUNT_FROZEN");
        assertThat(balanceOf(alice)).isEqualByComparingTo("100.00");
    }

    @Test
    void sameIdempotencyKeyAndPayloadReplaysWithoutSecondTransfer() {
        Account alice = newAccount("1000.00");
        Account bob = newAccount("0");
        String key = newKey();

        PaymentResult first = pay(key, alice, bob, "100");
        PaymentResult second = pay(key, alice, bob, "100.00"); // same amount, different scale

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.payment().getId()).isEqualTo(first.payment().getId());
        assertThat(balanceOf(alice)).isEqualByComparingTo("900.00");
        assertThat(balanceOf(bob)).isEqualByComparingTo("100.00");
    }

    @Test
    void reusingIdempotencyKeyWithDifferentPayloadIsConflict() {
        Account alice = newAccount("1000.00");
        Account bob = newAccount("0");
        String key = newKey();
        pay(key, alice, bob, "100.00");

        assertThatThrownBy(() -> pay(key, alice, bob, "200.00"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(409);
                    assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
                });
        assertThat(balanceOf(alice)).isEqualByComparingTo("900.00");
    }

    @Test
    void failedPaymentIsAlsoReplayedForTheSameKey() {
        Account alice = newAccount("10.00");
        Account bob = newAccount("0");
        String key = newKey();

        PaymentResult first = pay(key, alice, bob, "50.00");
        // Even if funds arrive later, the same key returns the original outcome instead of re-executing.
        Account topUp = newAccount("100.00");
        pay(topUp, alice, "100.00");
        PaymentResult replay = pay(key, alice, bob, "50.00");

        assertThat(first.payment().getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.payment().getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(balanceOf(bob)).isEqualByComparingTo("0");
    }

    @Test
    void validationErrorsDoNotConsumeTheIdempotencyKey() {
        Account alice = newAccount("100.00");
        String key = newKey();
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> paymentService.create(key, new PaymentDtos.CreatePaymentRequest(
                alice.getId(), unknown, new java.math.BigDecimal("10.00"), "x")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(404));

        Integer keys = jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ?", Integer.class, key);
        assertThat(keys).isZero();
    }

    @Test
    void rejectsSystemAccountsAndSubPaiseAmounts() {
        Account alice = newAccount("100.00");
        Account bob = newAccount("0");

        assertThatThrownBy(() -> paymentService.create(newKey(), new PaymentDtos.CreatePaymentRequest(
                alice.getId(), Account.SYSTEM_FUNDING_ACCOUNT_ID, new java.math.BigDecimal("1.00"), null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("SYSTEM_ACCOUNT"));
        assertThatThrownBy(() -> pay(newKey(), alice, bob, "1.001"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_AMOUNT"));
    }
}
