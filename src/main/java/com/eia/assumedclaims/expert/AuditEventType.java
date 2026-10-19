package com.eia.assumedclaims.expert;

/** Material change categories written to the append-only claim audit trail. */
public enum AuditEventType {
    CLAIM_REGISTERED,
    CLAIM_STATUS_CHANGED,
    LOSS_ADVICE_RECORDED,
    LOSS_ADVICE_ACCEPTED,
    BORDEREAU_INGESTED,
    RESERVE_CHANGED,
    CASH_CALL_REQUESTED,
    RECOVERY_RECORDED,
    COMMUTATION_EXECUTED,
    DISPUTE_RAISED,
    DISPUTE_RESOLVED,
    CLAIM_CLOSED
}
