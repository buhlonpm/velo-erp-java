package com.velo.gps.dto;

import com.velo.asset.WriteOffReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Списание SIM-карты: продать нельзя (sold отклоняется). */
public record WriteOffSimCardRequest(
        @NotNull WriteOffReason reason,
        @Size(max = 1000) String comment
) {
}
