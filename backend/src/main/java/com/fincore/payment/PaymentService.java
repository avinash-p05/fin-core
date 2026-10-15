package com.fincore.payment;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fincore.account.Account;
import com.fincore.account.AccountRepository;
import com.fincore.account.AccountService;
import com.fincore.account.AccountType;
import com.fincore.common.ApiException;
import com.fincore.common.Money;
import com.fincore.common.RowLocks;
import com.fincore.events.PaymentEvent;
import com.fincore.fraud.FraudAlertService;
import com.fincore.fraud.FraudAssessment;
import com.fincore.fraud.FraudService;
import com.fincore.idempotency.IdempotencyService;
import com.fincore.ledger.LedgerService;
import com.fincore.ledger.LedgerTransactionType;
import com.fincore.outbox.OutboxService;
import com.fincore.payment.PaymentDtos.CreatePaymentRequest;
import com.fincore.payment.PaymentDtos.PaymentResult;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {

    static final String IDEMPOTENCY_SCOPE = "PAYMENT";

    private final PaymentRepository payments;
    private final AccountRepository accounts;
    private final AccountService accountService;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;
    private final FraudService fraud;
    private final FraudAlertService alerts;
    private final RowLocks rowLocks;
    private final OutboxService outbox;

    public PaymentService(PaymentRepository payments, AccountRepository accounts, AccountService accountService,
                          LedgerService ledger, IdempotencyService idempotency, FraudService fraud,
                          FraudAlertService alerts, RowLocks rowLocks, OutboxService outbox) {
        this.payments = payments;
        this.accounts = accounts;
        this.accountService = accountService;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.fraud = fraud;
        this.alerts = alerts;
        this.rowLocks = rowLocks;
        this.outbox = outbox;
    }

    /**
     * Creates, screens and (if approved) executes a transfer in ONE database transaction:
     * idempotency claim, payment row, fraud decision/alert, account locks, balance updates and
     * ledger entries all commit together.
     * Business outcomes such as insufficient funds are persisted as FAILED payments (and replayed
     * for the same key); validation errors roll everything back, including the key claim.
     */
    @Transactional
    public PaymentResult create(String idempotencyKey, CreatePaymentRequest request) {
        BigDecimal amount = Money.normalize(request.amount());
        String description = request.description() == null ? null : request.description().trim();
        if (request.sourceAccountId().equals(request.destinationAccountId())) {
            throw ApiException.badRequest("SAME_ACCOUNT", "Source and destination accounts must differ");
        }
        String fingerprint = IdempotencyService.fingerprint(IDEMPOTENCY_SCOPE, request.sourceAccountId(),
                request.destinationAccountId(), amount.toPlainString(), Money.CURRENCY, description);

        Optional<UUID> existing = idempotency.claim(idempotencyKey, IDEMPOTENCY_SCOPE, fingerprint);
        if (existing.isPresent()) {
            return new PaymentResult(get(existing.get()), true);
        }

        requireCustomerAccount(request.sourceAccountId());
        requireCustomerAccount(request.destinationAccountId());

        Payment payment = payments.save(Payment.initiate(
                request.sourceAccountId(), request.destinationAccountId(), amount, description));
        idempotency.attach(idempotencyKey, payment.getId());

        // Fraud screening happens BEFORE any balance change. Only APPROVED payments move money now;
        // REVIEW holds the payment (no debit, no ledger) until an analyst decides.
        FraudAssessment assessment = fraud.evaluate(payment.getSourceAccountId(), payment.getDestinationAccountId(), amount);
        payment.recordFraudAssessment(assessment);
        switch (assessment.decision()) {
            case APPROVED -> settle(payment);
            case REVIEW -> {
                payment.markPendingReview();
                alerts.open(payment.getId(), assessment);
            }
            case DECLINED -> {
                payment.markDeclined(PaymentFailureReason.FRAUD_DECLINED);
                alerts.open(payment.getId(), assessment);
            }
        }
        return new PaymentResult(payment, false);
    }

    /** Analyst approved a held payment: execute it now (funds and account status are re-checked). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Payment releaseHeldPayment(UUID paymentId) {
        Payment payment = lockHeld(paymentId);
        settle(payment);
        return payment;
    }

    /** Analyst rejected a held payment: decline it. No money ever moved. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Payment rejectHeldPayment(UUID paymentId) {
        Payment payment = lockHeld(paymentId);
        payment.markDeclined(PaymentFailureReason.FRAUD_REVIEW_REJECTED);
        return payment;
    }

    private Payment lockHeld(UUID paymentId) {
        Payment payment = rowLocks.lockForUpdate(Payment.class, paymentId)
                .orElseThrow(() -> ApiException.notFound("Payment", paymentId));
        if (payment.getStatus() != PaymentStatus.PENDING_REVIEW) {
            throw ApiException.conflict("PAYMENT_NOT_HELD", "Payment " + paymentId + " is not pending review");
        }
        return payment;
    }

    /**
     * Moves the money for an authorised payment. Locks both accounts in deterministic order,
     * re-checks status and funds under the lock, then posts the ledger transaction. Outcomes that
     * are not errors (insufficient funds, frozen account) mark the payment FAILED without moving money.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void settle(Payment payment) {
        Map<UUID, Account> locked = accountService.lockInOrder(payment.getSourceAccountId(), payment.getDestinationAccountId());
        Account source = locked.get(payment.getSourceAccountId());
        Account destination = locked.get(payment.getDestinationAccountId());

        if (!source.isActive() || !destination.isActive()) {
            payment.markFailed(PaymentFailureReason.ACCOUNT_FROZEN);
            return;
        }
        if (!source.hasAvailable(payment.getAmount())) {
            payment.markFailed(PaymentFailureReason.INSUFFICIENT_FUNDS);
            return;
        }
        ledger.postTransfer(LedgerTransactionType.TRANSFER, payment.getId(), null,
                "Payment " + payment.getId(), source.getId(), destination.getId(), payment.getAmount(), locked);
        payment.markCompleted();
        // Same transaction as the ledger posting: the event exists iff the transfer committed.
        outbox.record(new PaymentEvent(UUID.randomUUID(), PaymentEvent.PAYMENT_COMPLETED, payment.getCompletedAt(),
                payment.getId(), source.getId(), destination.getId(), payment.getAmount().toPlainString(),
                payment.getCurrency(), null));
    }

    @Transactional(readOnly = true)
    public Payment get(UUID id) {
        return payments.findById(id).orElseThrow(() -> ApiException.notFound("Payment", id));
    }

    public record PaymentFilter(UUID accountId, PaymentStatus status) {
    }

    @Transactional(readOnly = true)
    public Page<Payment> list(PaymentFilter filter, Pageable pageable) {
        Specification<Payment> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (filter.accountId() != null) {
                p.add(cb.or(cb.equal(root.get("sourceAccountId"), filter.accountId()),
                        cb.equal(root.get("destinationAccountId"), filter.accountId())));
            }
            if (filter.status() != null) {
                p.add(cb.equal(root.get("status"), filter.status()));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        return payments.findAll(spec, pageable);
    }

    private void requireCustomerAccount(UUID accountId) {
        Account account = accounts.findById(accountId).orElseThrow(() -> ApiException.notFound("Account", accountId));
        if (account.getType() != AccountType.CUSTOMER) {
            throw ApiException.unprocessable("SYSTEM_ACCOUNT", "Payments can only move money between customer accounts");
        }
    }
}
