package com.fincore.payment;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fincore.account.Account;
import com.fincore.account.AccountService;
import com.fincore.common.ApiException;
import com.fincore.common.Money;
import com.fincore.common.RowLocks;
import com.fincore.idempotency.IdempotencyService;
import com.fincore.ledger.LedgerService;
import com.fincore.ledger.LedgerTransactionType;
import com.fincore.payment.PaymentDtos.RefundRequest;
import com.fincore.payment.PaymentDtos.RefundResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refunds never modify the original ledger transaction. Each refund posts a NEW compensating
 * REFUND transaction (DEBIT original payee, CREDIT original payer) and increments the payment's
 * refunded amount, bounded by the original amount.
 */
@Service
public class RefundService {

    static final String IDEMPOTENCY_SCOPE = "REFUND";

    private final RowLocks rowLocks;
    private final AccountService accountService;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;
    private final RefundRepository refunds;
    private final PaymentRepository payments;

    public RefundService(RowLocks rowLocks, AccountService accountService, LedgerService ledger,
                         IdempotencyService idempotency, RefundRepository refunds, PaymentRepository payments) {
        this.rowLocks = rowLocks;
        this.accountService = accountService;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.refunds = refunds;
        this.payments = payments;
    }

    @Transactional
    public RefundResult refund(UUID paymentId, String idempotencyKey, RefundRequest request) {
        BigDecimal requested = request.amount() == null ? null : Money.normalize(request.amount());
        String reason = request.reason() == null ? null : request.reason().trim();
        String fingerprint = IdempotencyService.fingerprint(IDEMPOTENCY_SCOPE, paymentId,
                requested == null ? "FULL_REMAINING" : requested.toPlainString(), reason);

        Optional<UUID> existing = idempotency.claim(idempotencyKey, IDEMPOTENCY_SCOPE, fingerprint);
        if (existing.isPresent()) {
            Refund refund = refunds.findById(existing.get()).orElseThrow();
            return new RefundResult(refund, payments.findById(paymentId).orElseThrow(), true);
        }

        // Locking the payment row serialises all refunds of this payment, so two concurrent
        // refunds cannot both pass the "remaining amount" check.
        Payment payment = rowLocks.lockForUpdate(Payment.class, paymentId)
                .orElseThrow(() -> ApiException.notFound("Payment", paymentId));
        if (payment.getStatus() != PaymentStatus.COMPLETED && payment.getStatus() != PaymentStatus.PARTIALLY_REFUNDED) {
            throw ApiException.unprocessable("PAYMENT_NOT_REFUNDABLE",
                    "Only completed payments can be refunded; payment is " + payment.getStatus());
        }
        BigDecimal amount = requested == null ? payment.refundableAmount() : requested;
        if (amount.compareTo(payment.refundableAmount()) > 0) {
            throw ApiException.unprocessable("REFUND_EXCEEDS_REMAINING",
                    "Refund of " + amount + " exceeds the remaining refundable amount " + payment.refundableAmount());
        }

        Map<UUID, Account> locked = accountService.lockInOrder(payment.getSourceAccountId(), payment.getDestinationAccountId());
        Account payee = locked.get(payment.getDestinationAccountId());
        Account payer = locked.get(payment.getSourceAccountId());
        if (!payee.isActive() || !payer.isActive()) {
            throw ApiException.unprocessable("ACCOUNT_FROZEN", "Refunds require both accounts to be active");
        }
        if (!payee.hasAvailable(amount)) {
            throw ApiException.unprocessable("INSUFFICIENT_FUNDS",
                    "The payee account no longer holds enough funds for this refund");
        }

        Refund refund = refunds.save(new Refund(paymentId, amount, reason));
        ledger.postTransfer(LedgerTransactionType.REFUND, paymentId, refund.getId(), "Refund of payment " + paymentId,
                payee.getId(), payer.getId(), amount, locked);
        payment.recordRefund(amount);
        idempotency.attach(idempotencyKey, refund.getId());
        return new RefundResult(refund, payment, false);
    }

    @Transactional(readOnly = true)
    public List<Refund> forPayment(UUID paymentId) {
        return refunds.findByPaymentIdOrderByCreatedAt(paymentId);
    }
}
