package com.eia.assumedclaims.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A claim commutation (SPEC §11.5.4); execution closes the claim. */
public record Commutation(
        UUID id,
        UUID tenantId,
        UUID assumedClaimId,
        String commutationNumber,
        CommutationType commutationType,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal offerAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal acceptedAmount,
        String currency,
        LocalDate effectiveDate,
        LocalDate valuationDate,
        CommutationBasis basis,
        UUID releaseDocumentId,
        CommutationStatus status,
        UUID approvedBy,
        ApproverRole approverRole,
        Instant approvedAt,
        Instant executedAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal savingsAmount,
        int version,
        Instant createdAt,
        Instant updatedAt
) {}
