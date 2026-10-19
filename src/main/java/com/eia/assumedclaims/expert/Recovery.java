package com.eia.assumedclaims.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A reinsurance recovery (SPEC §11.5.4), capped by the advised/paid loss (RNS-10). */
public record Recovery(
        UUID id,
        UUID tenantId,
        UUID assumedClaimId,
        RecoveryType recoveryType,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency,
        LocalDate receivedDate,
        RecoveryStatus status,
        UUID sourcePartyId,
        UUID sourceContractId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal expectedAmount,
        UUID paymentId,
        int version,
        Instant createdAt,
        Instant updatedAt
) {}
