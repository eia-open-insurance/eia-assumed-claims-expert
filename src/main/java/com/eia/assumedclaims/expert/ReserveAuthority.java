package com.eia.assumedclaims.expert;

/**
 * Reserve authority levels. Higher amounts and IBNR require a senior, actuarial, or underwriting
 * authority plus a named approver (SPEC RNS-05).
 */
public enum ReserveAuthority {
    SYSTEM,
    ADJUSTER,
    SENIOR_ADJUSTER,
    ACTUARIAL,
    UNDERWRITER
}
