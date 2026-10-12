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
import com.fincore.idempotency.IdempotencyService;
import com.fincore.ledger.LedgerService;
import com.fincore.ledger.LedgerTransactionType;
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

    public PaymentService(PaymentRepository payments, AccountRepository accounts, AccountService accountService,
                          LedgerService ledger, IdempotencyService idempotency) {
        this.payments = payments;
        this.accounts = accounts;
        this.accountService = accountService;
        this.ledger = ledger;
        this.idempotency = idempotency;
    }

    /**
     * Creates and (if possible) executes a transfer in ONE database transaction: idempotency
     * claim, payment row, account locks, balance updates and ledger entries all commit together.
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

        settle(payment);
        return new PaymentResult(payment, false);
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
