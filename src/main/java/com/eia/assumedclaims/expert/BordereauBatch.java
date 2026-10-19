package com.eia.assumedclaims.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A bordereau batch header. Lines become {@link LossAdvice} rows with source BORDEREAU. */
public record BordereauBatch(
        UUID id,
        UUID tenantId,
        UUID assumedClaimId,
        String bordereauReference,
        String cedentClaimNumber,
        LocalDate adviceDate,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal reportedAmountTotal,
        String currency,
        BordereauStatus status,
        AdviceSource source,
        int version,
        int lineCount,
        int acceptedCount,
        int duplicateCount,
        int rejectedCount,
        String payloadHash,
        Instant receivedAt,
        Instant createdAt,
        Instant updatedAt
) {}
