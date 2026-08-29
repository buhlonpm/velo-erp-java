package com.velo.customer.dto;

import jakarta.validation.constraints.Size;

/** Все поля опциональны — меняется только переданное. */
public record UpdateCustomerRequest(
        @Size(max = 255) String fullName,
        @Size(max = 32) String phone,
        @Size(max = 255) String email,
        @Size(max = 255) String address,
        @Size(max = 2000) String note
) {
}
