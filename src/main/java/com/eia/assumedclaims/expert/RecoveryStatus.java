package com.eia.assumedclaims.expert;

/** Recovery states from SPEC §11.5.4. */
public enum RecoveryStatus {
    IDENTIFIED,
    PURSUING,
    AGREED,
    RECEIVED,
    DENIED,
    ABANDONED;

    /**
     * A denied or abandoned recovery no longer consumes the recoverable loss capacity.
     */
    public boolean consumesCapacity() {
        return this != DENIED && this != ABANDONED;
    }
}
