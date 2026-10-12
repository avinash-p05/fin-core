package com.fincore.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import com.fincore.account.Account;
import com.fincore.ledger.LedgerDtos.VerificationStatus;
import com.fincore.payment.PaymentDtos.PaymentResult;
import com.fincore.support.Concurrently;
import com.fincore.support.Concurrently.Outcome;
import com.fincore.support.IntegrationTest;
import org.junit.jupiter.api.Test;

/** Real PostgreSQL row locks under real thread contention. */
class PaymentConcurrencyTest extends IntegrationTest {

    @Test
    void concurrentWithdrawalsNeverOverdraw() throws Exception {
        Account payer = newAccount("500.00");
        Account payee = newAccount("0");

        List<Callable<Payment>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> pay(payer, payee, "100.00"));
        }
        List<Outcome<Payment>> outcomes = Concurrently.run(tasks);

        assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
        long completed = outcomes.stream().filter(o -> o.value().getStatus() == PaymentStatus.COMPLETED).count();
        long failed = outcomes.stream().filter(o -> o.value().getStatus() == PaymentStatus.FAILED).count();
        assertThat(completed).isEqualTo(5);
        assertThat(failed).isEqualTo(5);
        assertThat(balanceOf(payer)).isEqualByComparingTo("0.00");
        assertThat(balanceOf(payee)).isEqualByComparingTo("500.00");
        assertThat(ledgerQueryService.verifyAccount(payer.getId()).status()).isEqualTo(VerificationStatus.VALID);
        assertThat(ledgerQueryService.verifyAccount(payee.getId()).status()).isEqualTo(VerificationStatus.VALID);
    }

    @Test
    void oppositeDirectionTransfersDoNotDeadlock() throws Exception {
        Account a = newAccount("1000.00");
        Account b = newAccount("1000.00");

        List<Callable<Payment>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> pay(a, b, "10.00"));
            tasks.add(() -> pay(b, a, "15.00"));
        }
        List<Outcome<Payment>> outcomes = Concurrently.run(tasks);

        // Deterministic lock ordering: no deadlock-detector aborts, every transfer completes.
        assertThat(outcomes).allSatisfy(o -> {
            assertThat(o.error()).isNull();
            assertThat(o.value().getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        });
        assertThat(balanceOf(a)).isEqualByComparingTo("1040.00");
        assertThat(balanceOf(b)).isEqualByComparingTo("960.00");
    }

    @Test
    void concurrentRequestsWithSameIdempotencyKeyExecuteOnce() throws Exception {
        Account payer = newAccount("1000.00");
        Account payee = newAccount("0");
        String key = newKey();

        List<Callable<PaymentResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> pay(key, payer, payee, "100.00"));
        }
        List<Outcome<PaymentResult>> outcomes = Concurrently.run(tasks);

        assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
        assertThat(outcomes.stream().map(o -> o.value().payment().getId()).distinct()).hasSize(1);
        assertThat(outcomes.stream().filter(o -> !o.value().replayed())).hasSize(1);
        assertThat(balanceOf(payer)).isEqualByComparingTo("900.00");
        assertThat(balanceOf(payee)).isEqualByComparingTo("100.00");
    }
}
