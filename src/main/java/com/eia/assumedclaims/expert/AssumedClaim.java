package com.eia.assumedclaims.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Persisted assumed claim. The book is fixed to ASSUMED for every row (SPEC §3.6.1).
 *
 * <p>{@code assignedAdjuster} and {@code assignedTeam} are the current claim
 * assignment; {@code assignedAt} is the instant of the last change and the
 * actor is stored in {@code assigned_by}. All three DTO fields are exposed so a
 * claim list or search can show and filter the current owner.</p>
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
        String assignedAdjuster,
        String assignedTeam,
        Instant assignedAt,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
    /**
     * Convenience constructor for a claim that has not been assigned yet. The
     * assignment columns are nullable, so a freshly registered claim (and the
     * fixtures that model one) can omit them.
     */
    public AssumedClaim(
            UUID id,
            UUID tenantId,
            String businessBook,
            String assumedClaimNumber,
            UUID contractId,
            String cedentClaimNumber,
            AssumedClaimStatus status,
            LocalDate lossDate,
            LocalDate reportDate,
            BigDecimal reserveAmount,
            String reserveCurrency,
            int version,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(id, tenantId, businessBook, assumedClaimNumber, contractId, cedentClaimNumber, status,
                lossDate, reportDate, reserveAmount, reserveCurrency, null, null, null, version,
                createdAt, updatedAt);
    }
}
