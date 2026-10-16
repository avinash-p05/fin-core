package com.fincore.dashboard;

import java.math.BigDecimal;

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

    DemoDataSeeder(CustomerRepository customerRepository, CustomerService customers, AccountService accounts) {
        this.customerRepository = customerRepository;
        this.customers = customers;
        this.accounts = accounts;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (customerRepository.count() > 0) {
            return;
        }
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
}
