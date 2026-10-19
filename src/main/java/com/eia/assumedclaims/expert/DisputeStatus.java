package com.eia.assumedclaims.expert;

/** Dispute states from SPEC §11.5.4. */
public enum DisputeStatus {
    OPEN,
    UNDER_REVIEW,
    NEGOTIATING,
    RESOLVED,
    ESCALATED,
    ARBITRATION,
    CLOSED;

    /** A dispute in any of these states blocks commutation and closure (RNS-11). */
    public boolean isOpen() {
        return this == OPEN
                || this == UNDER_REVIEW
                || this == NEGOTIATING
                || this == ESCALATED
                || this == ARBITRATION;
    }
}
