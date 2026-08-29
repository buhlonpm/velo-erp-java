package com.velo.gps.dto;

import jakarta.validation.constraints.Size;

/** Все поля опциональны — меняется только переданное. */
public record UpdateSimCardRequest(
        @Size(max = 32) String phoneNumber,
        @Size(max = 50) String operator,
        @Size(max = 255) String note
) {
}
