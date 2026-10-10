package com.fincore.customer;

import java.util.List;
import java.util.UUID;

import com.fincore.common.ApiException;
import com.fincore.customer.CustomerDtos.CreateCustomerRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerService {

    private final CustomerRepository customers;

    public CustomerService(CustomerRepository customers) {
        this.customers = customers;
    }

    @Transactional
    public Customer create(CreateCustomerRequest request) {
        String email = request.email().trim().toLowerCase();
        // Fast-path check for a friendly error; the unique constraint still guards concurrent inserts.
        if (customers.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("CUSTOMER_EXISTS", "A customer with this email already exists");
        }
        return customers.save(new Customer(request.fullName().trim(), email));
    }

    @Transactional(readOnly = true)
    public Customer get(UUID id) {
        return customers.findById(id).orElseThrow(() -> ApiException.notFound("Customer", id));
    }

    @Transactional(readOnly = true)
    public List<Customer> list() {
        return customers.findAll(Sort.by("createdAt"));
    }
}
