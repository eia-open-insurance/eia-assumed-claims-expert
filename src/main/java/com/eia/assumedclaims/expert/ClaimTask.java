package com.eia.assumedclaims.expert;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A claim task (diary item) scoped to a tenant and an ASSUMED-book claim.
 *
 * <p>{@code claimId} is the {@code assumed_claim_id} of the parent claim.
 * {@code version} is the task's optimistic concurrency token. It starts at 1
 * and increments on each accepted status change. Task writes also bump the
 * parent claim version so the claim event stream stays monotonic; the task
 * version is carried in the event payload as {@code taskVersion}.</p>
 *
 * <p>The public projection omits {@code tenantId}; the tenant is implied by the
 * request context (matching the Direct Claims task projection).</p>
 */
public record ClaimTask(
        UUID id,
        @JsonIgnore UUID tenantId,
        UUID claimId,
        String taskNumber,
        String title,
        String description,
        LocalDate dueDate,
        String priority,
        String status,
        String assignedTo,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
) {
}
