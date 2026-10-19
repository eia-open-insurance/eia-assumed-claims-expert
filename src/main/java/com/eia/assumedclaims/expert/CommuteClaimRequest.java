package com.eia.assumedclaims.expert;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CommuteClaimRequest(
        @NotNull(message = "offerAmount is required")
        @DecimalMin(value = "0.00", message = "offerAmount must be greater than or equal to zero")
        @Digits(integer = 15, fraction = 4)
        BigDecimal offerAmount,

        @NotNull(message = "acceptedAmount is required")
        @DecimalMin(value = "0.00", message = "acceptedAmount must be greater than or equal to zero")
        @Digits(integer = 15, fraction = 4)
        BigDecimal acceptedAmount,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "[A-Z]{3}", message = "currency must be a three-letter ISO 4217 code")
        String currency,

        @NotNull(message = "effectiveDate is required")
        LocalDate effectiveDate,

        LocalDate valuationDate,

        CommutationType commutationType,

        CommutationBasis basis,

        @NotNull(message = "approvedBy is required")
        UUID approvedBy,

        @NotNull(message = "approverRole is required")
        ApproverRole approverRole,

        @NotNull(message = "releaseDocumentId is required")
        UUID releaseDocumentId,

        @DecimalMin(value = "0.00", message = "savingsAmount must be greater than or equal to zero")
        @Digits(integer = 15, fraction = 4)
        BigDecimal savingsAmount
) {}
