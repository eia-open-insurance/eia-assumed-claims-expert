package com.eia.assumedclaims.expert;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Command to assign or reassign an assumed claim.
 *
 * <p>{@code assignedAdjuster} is a required free-form reference of at most 128
 * characters; {@code assignedTeam} is optional and at most 64 characters. Both
 * are trimmed. {@code expectedVersion} is the claim version the caller last saw
 * and is the optimistic-concurrency token: a stale value is answered with 409.
 * Repeating the current assignment with the current version is a no-op that
 * writes no event.</p>
 */
public record ClaimAssignmentRequest(
        @NotBlank(message = "assignedAdjuster is required")
        @Size(max = 128, message = "assignedAdjuster must be at most 128 characters")
        String assignedAdjuster,
        @Size(max = 64, message = "assignedTeam must be at most 64 characters")
        String assignedTeam,
        @NotNull(message = "expectedVersion is required")
        @PositiveOrZero(message = "expectedVersion must be greater than or equal to zero")
        Integer expectedVersion
) {
}
