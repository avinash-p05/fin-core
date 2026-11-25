package com.fincore.dashboard;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fincore.account.Account;
import com.fincore.fraud.FraudAlertService;
import com.fincore.fraud.FraudAlertService.AlertFilter;
import com.fincore.fraud.FraudAlertStatus;
import com.fincore.fraud.FraudDecision;
import com.fincore.payment.Payment;
import com.fincore.payment.PaymentDtos.CreatePaymentRequest;
import com.fincore.payment.PaymentService;
import org.springframework.data.domain.PageRequest;

import com.fincore.account.AccountDtos.CreateAccountRequest;
import com.fincore.account.AccountService;
import com.fincore.customer.Customer;
import com.fincore.customer.CustomerDtos.CreateCustomerRequest;
import com.fincore.customer.CustomerRepository;
import com.fincore.customer.CustomerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Optional demo data (enabled with FINCORE_DEMO_SEED=true). Goes through the normal services, so
 * opening balances are real OPENING_BALANCE ledger transactions. Runs only on an empty database.
 */
@Component
@ConditionalOnProperty(prefix = "fincore.demo", name = "seed", havingValue = "true")
class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final CustomerRepository customerRepository;
    private final CustomerService customers;
    private final AccountService accounts;
    private final PaymentService payments;
    private final FraudAlertService alerts;

    DemoDataSeeder(CustomerRepository customerRepository, CustomerService customers, AccountService accounts,
                   PaymentService payments, FraudAlertService alerts) {
        this.customerRepository = customerRepository;
        this.customers = customers;
        this.accounts = accounts;
        this.payments = payments;
        this.alerts = alerts;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (customerRepository.count() == 0) {
            seedCustomers();
        }
        ensureHeldPayments();
    }

    private void seedCustomers() {
        seed("Asha Rao", "asha.rao@example.com", "Asha - Savings", "500000.00");
        seed("Rohan Mehta", "rohan.mehta@example.com", "Rohan - Current", "250000.00");
        seed("Meera Iyer", "meera.iyer@example.com", "Meera - Savings", "50000.00");
        seed("Vikram Singh", "vikram.singh@example.com", "Vikram - Low balance", "1000.00");
        log.info("Seeded demo customers and accounts");
    }

    private void seed(String name, String email, String accountName, String opening) {
        Customer c = customers.create(new CreateCustomerRequest(name, email));
        accounts.create(new CreateAccountRequest(c.getId(), accountName, new BigDecimal(opening)));
    }

    /**
     * Demo visitors need a held payment to try the fraud copilot on. When no REVIEW alert is open
     * (none yet, or analysts decided them all), hold two more through the normal payment path:
     * a couple of small payments make the payee known, then a large one trips HIGH_VALUE only.
     */
    void ensureHeldPayments() {
        long open = alerts.list(new AlertFilter(FraudAlertStatus.OPEN, FraudDecision.REVIEW, null), PageRequest.of(0, 1))
                .getTotalElements();
        if (open > 0) {
            return;
        }
        Map<String, Account> byName = accounts.list(null).stream()
                .collect(Collectors.toMap(Account::getDisplayName, a -> a, (a, b) -> a));
        holdForReview(byName.get("Asha - Savings"), byName.get("Rohan - Current"), List.of("1500.00", "2200.00"),
                "150000.00", "Payment");
        holdForReview(byName.get("Rohan - Current"), byName.get("Meera - Savings"), List.of("900.00"),
                "120000.00", "Tuition fees");
    }

    /** Returns the held payment, or null if the accounts are missing or a payment could not be made. */
    Payment holdForReview(Account from, Account to, List<String> earlier, String amount, String description) {
        if (from == null || to == null) {
            return null;
        }
        try {
            for (String small : earlier) {
                pay(from, to, small, "Shared expenses");
            }
            Payment held = pay(from, to, amount, description);
            log.info("Seeded held payment {} ({} {}) for the fraud copilot demo", held.getId(), held.getStatus(), amount);
            return held;
        } catch (RuntimeException e) {
            log.warn("Could not seed a held demo payment from {}: {}", from.getDisplayName(), e.getMessage());
            return null;
        }
    }

    private Payment pay(Account from, Account to, String amount, String description) {
        return payments.create("demo-seed-" + UUID.randomUUID(),
                new CreatePaymentRequest(from.getId(), to.getId(), new BigDecimal(amount), description)).payment();
    }
}
