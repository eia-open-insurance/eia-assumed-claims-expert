package com.eia.assumedclaims.expert;

import java.time.Instant;
import java.util.UUID;

/** One append-only audit record for a material assumed-claim change. */
public record ClaimAuditEntry(
        UUID id,
        UUID tenantId,
        UUID assumedClaimId,
        AuditEventType eventType,
        String actor,
        String detail,
        Instant occurredAt,
        long sequence
) {}
