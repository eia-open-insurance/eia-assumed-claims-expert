package com.eia.assumedclaims.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Read projection of an assumed claim's ceded financial position (SPEC §11.5.4). Every amount is a
 * decimal string and the summary always carries the claim currency.
 */
public record FinancialSummary(
        UUID assumedClaimId,
        UUID tenantId,
        String currency,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cededPaidAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cededOutstandingAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cededReserveAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cededIbnrAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cededIncurredAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cededRecoveryAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal netCededAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cashCallAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal advisedLossAmount,
        Instant lastAdviceAt,
        int sourceVersion
) {}
