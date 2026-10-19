package com.eia.assumedclaims.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Year;
import java.time.ZoneOffset;

/** Business-number sequences. Numbers are owned by the expert so concurrent writers stay unique. */
@Component
public class NumberSequences {
    private final JdbcTemplate jdbc;

    public NumberSequences(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String nextClaimNumber() {
        return "RNS-" + Year.now(ZoneOffset.UTC).getValue() + "-" + String.format("%07d", next("assumed_claim_number_seq"));
    }

    public String nextCashCallNumber() {
        return "CSH-" + Year.now(ZoneOffset.UTC).getValue() + "-" + String.format("%06d", next("assumed_cash_call_number_seq"));
    }

    public String nextCommutationNumber() {
        return "COM-" + Year.now(ZoneOffset.UTC).getValue() + "-" + String.format("%06d", next("assumed_commutation_number_seq"));
    }

    public String nextDisputeNumber() {
        return "DSP-" + Year.now(ZoneOffset.UTC).getValue() + "-" + String.format("%06d", next("assumed_dispute_number_seq"));
    }

    private long next(String sequence) {
        Long value = jdbc.queryForObject("SELECT nextval('" + sequence + "')", Long.class);
        if (value == null) {
            throw new IllegalStateException(sequence + " returned no value");
        }
        return value;
    }
}
