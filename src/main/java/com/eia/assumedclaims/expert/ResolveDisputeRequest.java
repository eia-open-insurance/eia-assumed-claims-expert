package com.eia.assumedclaims.expert;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record ResolveDisputeRequest(
        @NotBlank(message = "resolution is required")
        @Size(max = 2000, message = "resolution must be at most 2000 characters")
        String resolution,

        @NotNull(message = "status is required")
        DisputeStatus status,

        LocalDate resolvedAt
) {}
