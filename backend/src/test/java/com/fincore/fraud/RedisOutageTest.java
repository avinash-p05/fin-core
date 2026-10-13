package com.fincore.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fincore.account.Account;
import com.fincore.payment.Payment;
import com.fincore.payment.PaymentStatus;
import com.fincore.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Redis outage behaviour through the full payment path. The tracker is replaced by a mock that
 * fails the same way the real one does when Redis is down (see VelocityTrackerTest for the real
 * connection-refused translation).
 */
class RedisOutageTest extends IntegrationTest {

    @MockitoBean
    VelocityTracker velocityTracker;

    @Autowired
    FraudAlertRepository alerts;

    @BeforeEach
    void redisDown() {
        when(velocityTracker.recordAndCount(any(), any()))
                .thenThrow(new VelocityUnavailableException("Connection refused", null));
    }

    @Test
    void paymentIsHeldForReviewInsteadOfSilentlyApproved() {
        Account payer = newAccount("1000.00");
        Account payee = newAccount("0");

        Payment payment = pay(payer, payee, "10.00");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING_REVIEW);
        assertThat(payment.getFraudDecision()).isEqualTo(FraudDecision.REVIEW);
        assertThat(payment.getFraudRules()).contains(FraudRule.VELOCITY_CHECK_UNAVAILABLE);
        assertThat(balanceOf(payer)).isEqualByComparingTo("1000.00");
        assertThat(alerts.findByPaymentId(payment.getId())).isPresent();
    }
}
