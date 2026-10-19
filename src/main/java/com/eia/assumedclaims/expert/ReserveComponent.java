package com.eia.assumedclaims.expert;

/** Reserve components from SPEC §11.5.4. Components are never netted (RNS-04). */
public enum ReserveComponent {
    CASE,
    IBNR,
    IBNER,
    OUTSTANDING,
    ALAE,
    ULAE,
    CASH_LOSS,
    SALVAGE,
    SUBROGATION
}
