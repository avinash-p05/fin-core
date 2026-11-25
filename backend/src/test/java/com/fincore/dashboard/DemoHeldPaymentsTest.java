package com.fincore.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fincore.account.Account;
import com.fincore.fraud.FraudAlertRepository;
import com.fincore.fraud.FraudAlertService;
import com.fincore.fraud.FraudAlertStatus;
import com.fincore.fraud.FraudDecision;
import com.fincore.customer.CustomerRepository;
import com.fincore.payment.Payment;
import com.fincore.payment.PaymentStatus;
import com.fincore.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The demo seeder's held payments really are held for REVIEW, so visitors can try the copilot on them. */
class DemoHeldPaymentsTest extends IntegrationTest {

    @Autowired
    CustomerRepository customerRepository;

    @Autowired
    FraudAlertService alertService;

    @Autowired
    FraudAlertRepository alertRepository;

    @Test
    void seededPaymentIsHeldWithAnOpenReviewAlert() {
        var seeder = new DemoDataSeeder(customerRepository, customerService, accountService, paymentService, alertService);
        Account from = newAccount("500000.00");
        Account to = newAccount("0");

        Payment held = seeder.holdForReview(from, to, List.of("1500.00", "2200.00"), "150000.00", "Payment");

        assertThat(held.getStatus()).isEqualTo(PaymentStatus.PENDING_REVIEW);
        var alert = alertRepository.findByPaymentId(held.getId()).orElseThrow();
        assertThat(alert.getDecision()).isEqualTo(FraudDecision.REVIEW);
        assertThat(alert.getStatus()).isEqualTo(FraudAlertStatus.OPEN);
        assertThat(balanceOf(from)).isEqualByComparingTo("496300.00"); // only the two small payments moved
    }

    @Test
    void missingAccountsAreSkipped() {
        var seeder = new DemoDataSeeder(customerRepository, customerService, accountService, paymentService, alertService);

        assertThat(seeder.holdForReview(null, newAccount("0"), List.of(), "150000.00", "x")).isNull();
    }
}
