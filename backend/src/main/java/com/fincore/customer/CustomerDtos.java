package com.fincore.customer;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class CustomerDtos {

    private CustomerDtos() {
    }

    public record CreateCustomerRequest(
            @NotBlank @Size(max = 200) String fullName,
            @NotBlank @Email @Size(max = 320) String email) {
    }

    public record CustomerResponse(UUID id, String fullName, String email, Instant createdAt) {

        static CustomerResponse from(Customer c) {
            return new CustomerResponse(c.getId(), c.getFullName(), c.getEmail(), c.getCreatedAt());
        }
    }
}
