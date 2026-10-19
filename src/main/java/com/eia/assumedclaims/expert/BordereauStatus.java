package com.eia.assumedclaims.expert;

/** Bordereau batch states. A batch can be partially accepted, fully duplicated, or rejected. */
public enum BordereauStatus {
    PROCESSED,
    PARTIAL,
    DUPLICATE,
    REJECTED
}
