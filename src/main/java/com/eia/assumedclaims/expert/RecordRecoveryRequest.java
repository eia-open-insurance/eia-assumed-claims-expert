package com.eia.assumedclaims.expert;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record RecordRecoveryRequest(
        @NotNull(message = "recoveryType is required")
        RecoveryType recoveryType,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.01", message = "amount must be greater than zero")
        @Digits(integer = 15, fraction = 4)
        BigDecimal amount,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "[A-Z]{3}", message = "currency must be a three-letter ISO 4217 code")
        String currency,

        LocalDate receivedDate,

        RecoveryStatus status,

        UUID sourcePartyId,

        UUID sourceContractId,

        @DecimalMin(value = "0.00", message = "expectedAmount must be greater than or equal to zero")
        @Digits(integer = 15, fraction = 4)
        BigDecimal expectedAmount,

        UUID paymentId
) {}
