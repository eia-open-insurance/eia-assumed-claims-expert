package com.eia.assumedclaims.expert;

/** Reserve movement reasons from SPEC §11.5.4. A missing reason is rejected. */
public enum ReserveReason {
    INITIAL,
    ADVICE,
    DEVELOPMENT,
    PAYMENT_APPLIED,
    RECOVERY_APPLIED,
    REVIEW,
    COMMUTATION,
    CLOSE
}
