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

public record CreateAssumedClaimRequest(
        @NotNull(message = "tenantId is required")
        UUID tenantId,

        @NotNull(message = "contractId is required")
        UUID contractId,

        @Size(max = 64, message = "cedentClaimNumber must be at most 64 characters")
        String cedentClaimNumber,

        LocalDate lossDate,

        LocalDate reportDate,

        @NotNull(message = "reserveAmount is required")
        @DecimalMin(value = "0.00", message = "reserveAmount must be greater than or equal to zero")
        @Digits(integer = 15, fraction = 4, message = "reserveAmount must have at most 15 integer and 4 fractional digits")
        BigDecimal reserveAmount,

        @NotBlank(message = "reserveCurrency is required")
        @Pattern(regexp = "[A-Z]{3}", message = "reserveCurrency must be a three-letter ISO 4217 code")
        String reserveCurrency
) {}
