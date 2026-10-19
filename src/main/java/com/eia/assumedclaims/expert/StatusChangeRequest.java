package com.eia.assumedclaims.expert;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record StatusChangeRequest(
        @NotNull(message = "tenantId is required")
        UUID tenantId,

        @NotNull(message = "targetStatus is required")
        AssumedClaimStatus targetStatus,

        @NotNull(message = "expectedVersion is required")
        @Min(value = 0, message = "expectedVersion must not be negative")
        Integer expectedVersion
) {}
