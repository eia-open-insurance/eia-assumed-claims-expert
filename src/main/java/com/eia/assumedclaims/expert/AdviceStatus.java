package com.eia.assumedclaims.expert;

/**
 * Loss-advice states. {@code POSTED} is the accepted state: recording an advice alone does not
 * validate it, and an assumed claim cannot be opened until at least one advice is accepted.
 */
public enum AdviceStatus {
    RECEIVED,
    VALIDATED,
    POSTED,
    REJECTED,
    SUPERSEDED,
    DUPLICATE
}
