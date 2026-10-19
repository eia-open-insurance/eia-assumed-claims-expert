package com.eia.assumedclaims.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Persisted assumed claim. The book is fixed to ASSUMED for every row (SPEC §3.6.1).
 */
public record AssumedClaim(
        UUID id,
        UUID tenantId,
        String businessBook,
        String assumedClaimNumber,
        UUID contractId,
        String cedentClaimNumber,
        AssumedClaimStatus status,
        LocalDate lossDate,
        LocalDate reportDate,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal reserveAmount,
        String reserveCurrency,
        int version,
        Instant createdAt,
        Instant updatedAt
) {}
