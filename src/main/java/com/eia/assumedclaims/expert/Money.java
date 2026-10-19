package com.eia.assumedclaims.expert;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Money normalization for SPEC §10.3: numeric(19,4) storage and decimal string JSON. */
final class Money {
    private Money() {}

    static BigDecimal amount(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
    }
}
