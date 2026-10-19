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

public record ReserveChangeRequest(
        @NotNull(message = "component is required")
        ReserveComponent component,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.00", message = "amount must be greater than or equal to zero")
        @Digits(integer = 15, fraction = 4)
        BigDecimal amount,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "[A-Z]{3}", message = "currency must be a three-letter ISO 4217 code")
        String currency,

        @NotNull(message = "effectiveDate is required")
        LocalDate effectiveDate,

        @NotNull(message = "reasonCode is required")
        ReserveReason reasonCode,

        @Size(max = 200, message = "reasonText must be at most 200 characters")
        String reasonText,

        @NotNull(message = "authority is required")
        ReserveAuthority authority,

        UUID approvedBy
) {}
