package com.fincore.customer;

import java.util.List;
import java.util.UUID;

import com.fincore.customer.CustomerDtos.CreateCustomerRequest;
import com.fincore.customer.CustomerDtos.CustomerResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customers")
@Tag(name = "Customers")
public class CustomerController {

    private final CustomerService service;

    public CustomerController(CustomerService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a customer")
    public CustomerResponse create(@Valid @RequestBody CreateCustomerRequest request) {
        return CustomerResponse.from(service.create(request));
    }

    @GetMapping
    @Operation(summary = "List customers")
    public List<CustomerResponse> list() {
        return service.list().stream().map(CustomerResponse::from).toList();
    }

    @GetMapping("/{customerId}")
    @Operation(summary = "Get a customer")
    public CustomerResponse get(@PathVariable UUID customerId) {
        return CustomerResponse.from(service.get(customerId));
    }
}
