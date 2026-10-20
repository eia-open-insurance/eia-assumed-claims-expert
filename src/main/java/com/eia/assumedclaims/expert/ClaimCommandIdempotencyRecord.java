package com.eia.assumedclaims.expert;

import java.util.UUID;

/**
 * Stored idempotency row for a claim task or note create command. The response
 * body is the original 201 projection serialized as JSON, so a replay returns
 * the same representation without reloading or re-creating the resource.
 */
public record ClaimCommandIdempotencyRecord(
        String commandType,
        String requestHash,
        UUID claimId,
        String responseBody
) {
}
