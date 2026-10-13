package com.fincore.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import com.fincore.account.Account;
import com.fincore.common.ApiException;
import com.fincore.ledger.EntryDirection;
import com.fincore.ledger.LedgerDtos.LedgerTransactionResponse;
import com.fincore.ledger.LedgerDtos.VerificationStatus;
import com.fincore.ledger.LedgerTransactionType;
import com.fincore.payment.PaymentDtos.RefundRequest;
import com.fincore.payment.PaymentDtos.RefundResult;
import com.fincore.support.Concurrently;
import com.fincore.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RefundIntegrationTest extends IntegrationTest {

    @Autowired
    RefundService refundService;

    @Test
    void fullRefundPostsCompensatingTransactionAndKeepsOriginal() {
        Account payer = newAccount("1000.00");
        Account payee = newAccount("0");
        Payment payment = pay(payer, payee, "400.00");
        List<LedgerTransactionResponse> before = ledgerQueryService.forPayment(payment.getId());

        RefundResult result = refund(payment, newKey(), null);

        assertThat(result.refund().getAmount()).isEqualByComparingTo("400.00");
        assertThat(result.payment().getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(balanceOf(payer)).isEqualByComparingTo("1000.00");
        assertThat(balanceOf(payee)).isEqualByComparingTo("0.00");

        List<LedgerTransactionResponse> after = ledgerQueryService.forPayment(payment.getId());
        assertThat(after).hasSize(2);
        // The original TRANSFER is untouched; the REFUND reverses directions.
        assertThat(after.getFirst()).isEqualTo(before.getFirst());
        LedgerTransactionResponse refundTx = after.get(1);
        assertThat(refundTx.type()).isEqualTo(LedgerTransactionType.REFUND);
        assertThat(refundTx.refundId()).isEqualTo(result.refund().getId());
        assertThat(refundTx.balanced()).isTrue();
        assertThat(refundTx.entries()).anySatisfy(e -> {
            assertThat(e.accountId()).isEqualTo(payee.getId());
            assertThat(e.direction()).isEqualTo(EntryDirection.DEBIT);
        });
        assertThat(ledgerQueryService.verifyAccount(payer.getId()).status()).isEqualTo(VerificationStatus.VALID);
        assertThat(ledgerQueryService.verifyAccount(payee.getId()).status()).isEqualTo(VerificationStatus.VALID);
    }

    @Test
    void partialRefundsCannotExceedOriginalAmount() {
        Account payer = newAccount("1000.00");
        Account payee = newAccount("0");
        Payment payment = pay(payer, payee, "300.00");

        assertThat(refund(payment, newKey(), "100.00").payment().getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(refund(payment, newKey(), "150.00").payment().getRefundedAmount()).isEqualByComparingTo("250.00");

        assertThatThrownBy(() -> refund(payment, newKey(), "50.01"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("REFUND_EXCEEDS_REMAINING"));

        RefundResult last = refund(payment, newKey(), null); // remaining 50.00
        assertThat(last.refund().getAmount()).isEqualByComparingTo("50.00");
        assertThat(last.payment().getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThatThrownBy(() -> refund(payment, newKey(), "0.01"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("PAYMENT_NOT_REFUNDABLE"));
        assertThat(balanceOf(payer)).isEqualByComparingTo("1000.00");
    }

    @Test
    void duplicateRefundRequestWithSameKeyIsReplayed() {
        Account payer = newAccount("1000.00");
        Account payee = newAccount("0");
        Payment payment = pay(payer, payee, "200.00");
        String key = newKey();

        RefundResult first = refund(payment, key, "80.00");
        RefundResult second = refund(payment, key, "80.00");

        assertThat(second.replayed()).isTrue();
        assertThat(second.refund().getId()).isEqualTo(first.refund().getId());
        assertThat(second.payment().getRefundedAmount()).isEqualByComparingTo("80.00");
        assertThat(balanceOf(payee)).isEqualByComparingTo("120.00");

        assertThatThrownBy(() -> refund(payment, key, "90.00"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(409));
    }

    @Test
    void concurrentFullRefundsWithDifferentKeysRefundOnce() throws Exception {
        Account payer = newAccount("1000.00");
        Account payee = newAccount("0");
        Payment payment = pay(payer, payee, "500.00");

        List<Callable<RefundResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> refund(payment, newKey(), null));
        }
        var outcomes = Concurrently.run(tasks);

        assertThat(outcomes.stream().filter(o -> o.error() == null)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> o.error() instanceof ApiException)).hasSize(5);
        assertThat(balanceOf(payer)).isEqualByComparingTo("1000.00");
        assertThat(paymentService.get(payment.getId()).getRefundedAmount()).isEqualByComparingTo("500.00");
    }

    @Test
    void cannotRefundFailedOrHeldPayments() {
        Account payer = newAccount("10.00");
        Account payee = newAccount("0");
        Payment failed = pay(payer, payee, "50.00");

        assertThatThrownBy(() -> refund(failed, newKey(), null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("PAYMENT_NOT_REFUNDABLE"));
    }

    @Test
    void refundFailsIfPayeeAlreadySpentTheFunds() {
        Account payer = newAccount("1000.00");
        Account payee = newAccount("0");
        Account elsewhere = newAccount("0");
        Payment payment = pay(payer, payee, "100.00");
        pay(payee, elsewhere, "90.00");

        assertThatThrownBy(() -> refund(payment, newKey(), null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INSUFFICIENT_FUNDS"));
        assertThat(balanceOf(payee)).isEqualByComparingTo("10.00");
        assertThat(paymentService.get(payment.getId()).getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    }

    private RefundResult refund(Payment payment, String key, String amount) {
        return refundService.refund(payment.getId(), key,
                new RefundRequest(amount == null ? null : new BigDecimal(amount), "customer request"));
    }
}
