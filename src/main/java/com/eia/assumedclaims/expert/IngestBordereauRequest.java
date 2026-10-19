package com.eia.assumedclaims.expert;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record IngestBordereauRequest(
        @NotBlank(message = "bordereauReference is required")
        @Size(max = 64, message = "bordereauReference must be at most 64 characters")
        String bordereauReference,

        @NotNull(message = "adviceDate is required")
        LocalDate adviceDate,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "[A-Z]{3}", message = "currency must be a three-letter ISO 4217 code")
        String currency,

        @Size(max = 64, message = "cedentClaimNumber must be at most 64 characters")
        String cedentClaimNumber,

        @NotEmpty(message = "lines is required")
        @Size(max = 500, message = "a bordereau batch may contain at most 500 lines")
        List<@Valid BordereauLine> lines
) {
    public record BordereauLine(
            @NotBlank(message = "adviceNumber is required")
            @Size(max = 32, message = "adviceNumber must be at most 32 characters")
            String adviceNumber,

            LocalDate adviceDate,

            @NotNull(message = "reportedAmount is required")
            java.math.BigDecimal reportedAmount,

            java.math.BigDecimal paidAmount,

            java.math.BigDecimal outstandingAmount,

            java.math.BigDecimal expenseAmount,

            AdviceType adviceType
    ) {}
}
