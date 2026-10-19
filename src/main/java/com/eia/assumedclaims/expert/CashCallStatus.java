package com.eia.assumedclaims.expert;

/** Cash-call states from SPEC §11.5.4. */
public enum CashCallStatus {
    DRAFT,
    SUBMITTED,
    APPROVED,
    PAID,
    REJECTED,
    CANCELLED;

    public boolean isOutstanding() {
        return this == SUBMITTED || this == APPROVED;
    }
}
