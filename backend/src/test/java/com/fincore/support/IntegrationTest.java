package com.fincore.support;

import java.math.BigDecimal;
import java.util.UUID;

import com.fincore.account.Account;
import com.fincore.account.AccountDtos.CreateAccountRequest;
import com.fincore.account.AccountService;
import com.fincore.customer.Customer;
import com.fincore.customer.CustomerDtos.CreateCustomerRequest;
import com.fincore.customer.CustomerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Base class for integration tests against real PostgreSQL. Every test creates its own customers
 * and accounts (random emails/ids) so tests never depend on each other's data.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
public abstract class IntegrationTest {

    @Autowired
    protected CustomerService customerService;

    @Autowired
    protected AccountService accountService;

    protected Customer newCustomer() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        return customerService.create(new CreateCustomerRequest("Test " + tag, "test-" + tag + "@example.com"));
    }

    protected Account newAccount(BigDecimal openingBalance) {
        return accountService.create(new CreateAccountRequest(newCustomer().getId(), "Savings", openingBalance));
    }
}
