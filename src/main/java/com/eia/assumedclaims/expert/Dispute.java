package com.eia.assumedclaims.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A claim dispute (SPEC §11.5.4). An open dispute blocks commutation and closure (RNS-11). */
public record Dispute(
        UUID id,
        UUID tenantId,
        UUID assumedClaimId,
        String disputeNumber,
        DisputeType disputeType,
        String reason,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal contestedAmount,
        String currency,
        DisputeStatus status,
        String resolution,
        LocalDate raisedAt,
        LocalDate resolvedAt,
        boolean arbitrationFlag,
        UUID raisedByPartyId,
        int version,
        Instant createdAt,
        Instant updatedAt
) {}
