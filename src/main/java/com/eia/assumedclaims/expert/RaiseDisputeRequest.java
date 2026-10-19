package com.eia.assumedclaims.expert;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record RaiseDisputeRequest(
        @NotNull(message = "disputeType is required")
        DisputeType disputeType,

        @NotBlank(message = "reason is required")
        @Size(max = 2000, message = "reason must be at most 2000 characters")
        String reason,

        @NotNull(message = "contestedAmount is required")
        @DecimalMin(value = "0.00", message = "contestedAmount must be greater than or equal to zero")
        @Digits(integer = 15, fraction = 4)
        BigDecimal contestedAmount,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "[A-Z]{3}", message = "currency must be a three-letter ISO 4217 code")
        String currency,

        LocalDate raisedAt,

        boolean arbitrationFlag,

        UUID raisedByPartyId
) {}
