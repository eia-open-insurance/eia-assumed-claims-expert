package com.eia.assumedclaims.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One append-only reserve movement. The claim's current reserve is derived from the latest current
 * row per component; CASE and IBNR remain separate (RNS-04).
 */
public record ReserveHistoryEntry(
        UUID id,
        UUID tenantId,
        UUID assumedClaimId,
        ReserveComponent component,
        int sequence,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency,
        LocalDate effectiveDate,
        ReserveReason reasonCode,
        String reasonText,
        ReserveAuthority authority,
        boolean authorityRequired,
        UUID approvedBy,
        Instant approvedAt,
        boolean isCurrent,
        int version,
        UUID createdBy,
        Instant createdAt
) {}
