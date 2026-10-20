package com.eia.assumedclaims.expert;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.UUID;

/**
 * An append-only diary note on an assumed claim. {@code claimId} is the
 * {@code assumed_claim_id} of the parent claim. Notes have no version and no
 * update or delete route; the row and its {@code assumed.claim.note-added}
 * event are written in one transaction. The public projection omits
 * {@code tenantId}, which is implied by the request context.
 */
public record ClaimNote(
        UUID id,
        @JsonIgnore UUID tenantId,
        UUID claimId,
        String noteNumber,
        String noteType,
        String body,
        String author,
        Instant createdAt
) {
}
