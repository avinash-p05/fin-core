package com.fincore.fraud;

import java.util.UUID;

import com.fincore.common.ApiException;
import com.fincore.common.RowLocks;
import com.fincore.payment.Payment;
import com.fincore.payment.PaymentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Analyst review workflow. The alert row is locked first, so two analysts acting on the same
 * alert are serialised and the second sees the terminal status (HTTP 409) instead of executing
 * the payment twice.
 */
@Service
public class FraudReviewService {

    private final RowLocks rowLocks;
    private final PaymentService payments;

    public FraudReviewService(RowLocks rowLocks, PaymentService payments) {
        this.rowLocks = rowLocks;
        this.payments = payments;
    }

    public record ReviewOutcome(FraudAlert alert, Payment payment) {
    }

    @Transactional
    public ReviewOutcome updateStatus(UUID alertId, FraudAlertStatus target, String note, String reviewer) {
        FraudAlert alert = rowLocks.lockForUpdate(FraudAlert.class, alertId)
                .orElseThrow(() -> ApiException.notFound("Fraud alert", alertId));
        alert.transitionTo(target, reviewer, note);

        Payment payment = switch (target) {
            // Funds and account status are re-checked under lock; the payment may still end FAILED.
            case APPROVED -> payments.releaseHeldPayment(alert.getPaymentId());
            case REJECTED -> payments.rejectHeldPayment(alert.getPaymentId());
            default -> payments.get(alert.getPaymentId());
        };
        return new ReviewOutcome(alert, payment);
    }
}
