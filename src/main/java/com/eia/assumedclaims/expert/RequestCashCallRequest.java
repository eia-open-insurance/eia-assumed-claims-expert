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

public record RequestCashCallRequest(
        @NotNull(message = "requestedAmount is required")
        @DecimalMin(value = "0.01", message = "requestedAmount must be greater than zero")
        @Digits(integer = 15, fraction = 4)
        BigDecimal requestedAmount,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "[A-Z]{3}", message = "currency must be a three-letter ISO 4217 code")
        String currency,

        @NotNull(message = "reason is required")
        CashCallReason reason,

        @Size(max = 2000, message = "reasonText must be at most 2000 characters")
        String reasonText,

        LocalDate requestedAt,

        LocalDate dueDate,

        UUID approvedBy,

        ApproverRole approverRole,

        UUID paymentId
) {}
