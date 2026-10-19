package com.eia.assumedclaims.expert;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record RecordAdviceRequest(
        @NotBlank(message = "adviceNumber is required")
        @Size(max = 32, message = "adviceNumber must be at most 32 characters")
        String adviceNumber,

        @NotNull(message = "adviceType is required")
        AdviceType adviceType,

        @NotNull(message = "adviceDate is required")
        LocalDate adviceDate,

        LocalDate receivedDate,

        LocalDate periodFrom,

        LocalDate periodTo,

        @NotNull(message = "reportedAmount is required")
        @DecimalMin(value = "0.00", message = "reportedAmount must be greater than or equal to zero")
        @Digits(integer = 15, fraction = 4)
        BigDecimal reportedAmount,

        @DecimalMin(value = "0.00", message = "paidAmount must be greater than or equal to zero")
        @Digits(integer = 15, fraction = 4)
        BigDecimal paidAmount,

        @DecimalMin(value = "0.00", message = "outstandingAmount must be greater than or equal to zero")
        @Digits(integer = 15, fraction = 4)
        BigDecimal outstandingAmount,

        @DecimalMin(value = "0.00", message = "expenseAmount must be greater than or equal to zero")
        @Digits(integer = 15, fraction = 4)
        BigDecimal expenseAmount,

        @DecimalMin(value = "0.00", message = "reserveAmount must be greater than or equal to zero")
        @Digits(integer = 15, fraction = 4)
        BigDecimal reserveAmount,

        @DecimalMin(value = "0.00", message = "recoveryAmount must be greater than or equal to zero")
        @Digits(integer = 15, fraction = 4)
        BigDecimal recoveryAmount,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "[A-Z]{3}", message = "currency must be a three-letter ISO 4217 code")
        String currency,

        @DecimalMin(value = "0.000000", message = "cessionPct must be between 0 and 1")
        @DecimalMax(value = "1.000000", message = "cessionPct must be between 0 and 1")
        @Digits(integer = 1, fraction = 6)
        BigDecimal cessionPct,

        @DecimalMin(value = "0.00", message = "cededAmount must be greater than or equal to zero")
        @Digits(integer = 15, fraction = 4)
        BigDecimal cededAmount,

        Boolean layerAttachmentMet,

        UUID sourceDocumentId,

        @Size(max = 64, message = "cedentClaimNumber must be at most 64 characters")
        String cedentClaimNumber,

        @Size(max = 2000, message = "notes must be at most 2000 characters")
        String notes
) {}
