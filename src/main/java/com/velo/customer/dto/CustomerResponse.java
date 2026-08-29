package com.velo.customer.dto;

import com.velo.customer.Customer;

import java.util.UUID;

public record CustomerResponse(
        UUID id,
        String fullName,
        String phone,
        String email,
        String address,
        String note,
        int rentalsCount
) {
    public static CustomerResponse from(Customer customer, int rentalsCount) {
        return new CustomerResponse(
                customer.getId(),
                customer.getFullName(),
                customer.getPhone(),
                customer.getEmail(),
                customer.getAddress(),
                customer.getNote(),
                rentalsCount);
    }
}
