package com.fincore.support;

import java.math.BigDecimal;
import java.util.UUID;

import com.fincore.account.Account;
import com.fincore.account.AccountDtos.CreateAccountRequest;
import com.fincore.account.AccountRepository;
import com.fincore.account.AccountService;
import com.fincore.customer.Customer;
import com.fincore.customer.CustomerDtos.CreateCustomerRequest;
import com.fincore.customer.CustomerService;
import com.fincore.ledger.LedgerQueryService;
import com.fincore.payment.Payment;
import com.fincore.payment.PaymentDtos.CreatePaymentRequest;
import com.fincore.payment.PaymentDtos.PaymentResult;
import com.fincore.payment.PaymentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Base class for integration tests against real infrastructure. Every test creates its own
 * customers and accounts (random emails/ids) so tests never depend on each other's data.
 */
@SpringBootTest(properties = {
        // Concurrency tests send up to 16 payments per source account; keep velocity out of their way.
        "fincore.fraud.velocity.max-transactions=" + IntegrationTest.TEST_VELOCITY_MAX
})
@Import(TestcontainersConfig.class)
public abstract class IntegrationTest {

    public static final int TEST_VELOCITY_MAX = 20;

    @Autowired
    protected CustomerService customerService;

    @Autowired
    protected AccountService accountService;

    @Autowired
    protected AccountRepository accountRepository;

    @Autowired
    protected PaymentService paymentService;

    @Autowired
    protected LedgerQueryService ledgerQueryService;

    @Autowired
    protected JdbcTemplate jdbc;

    protected Customer newCustomer() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        return customerService.create(new CreateCustomerRequest("Test " + tag, "test-" + tag + "@example.com"));
    }

    protected Account newAccount(String openingBalance) {
        return accountService.create(new CreateAccountRequest(newCustomer().getId(), "Savings", new BigDecimal(openingBalance)));
    }

    protected PaymentResult pay(String key, Account from, Account to, String amount) {
        return paymentService.create(key, new CreatePaymentRequest(from.getId(), to.getId(), new BigDecimal(amount), "test"));
    }

    protected Payment pay(Account from, Account to, String amount) {
        return pay(newKey(), from, to, amount).payment();
    }

    protected static String newKey() {
        return "test-" + UUID.randomUUID();
    }

    protected BigDecimal balanceOf(Account account) {
        return accountRepository.findById(account.getId()).orElseThrow().getBalance();
    }

    protected static BigDecimal inr(String amount) {
        return new BigDecimal(amount).setScale(2);
    }
}
