package com.eia.assumedclaims.expert;

/**
 * Result of a create command. {@code replayed} is true when an existing row was
 * returned for a repeated {@code Idempotency-Key} instead of creating a new one.
 */
public record CommandResult<T>(T body, boolean replayed) {
}
