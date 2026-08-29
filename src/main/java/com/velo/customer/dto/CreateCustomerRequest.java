package com.velo.customer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCustomerRequest(
        @NotBlank @Size(max = 255) String fullName,
        @NotBlank @Size(max = 32) String phone,
        @Size(max = 255) String email,
        @Size(max = 255) String address,
        @Size(max = 2000) String note
) {
}
