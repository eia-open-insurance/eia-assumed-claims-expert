package com.eia.assumedclaims.expert;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssumedClaimStatusTest {
    private static final Map<AssumedClaimStatus, Set<AssumedClaimStatus>> SPEC_LIFECYCLE = Map.ofEntries(
            Map.entry(AssumedClaimStatus.NOTIFIED, Set.of(AssumedClaimStatus.OPEN)),
            Map.entry(AssumedClaimStatus.OPEN, Set.of(
                    AssumedClaimStatus.RESERVED,
                    AssumedClaimStatus.DENIED,
                    AssumedClaimStatus.WITHDRAWN,
                    AssumedClaimStatus.DISPUTED)),
            Map.entry(AssumedClaimStatus.RESERVED, Set.of(
                    AssumedClaimStatus.IN_ADJUSTMENT,
                    AssumedClaimStatus.COMMUTED)),
            Map.entry(AssumedClaimStatus.IN_ADJUSTMENT, Set.of(
                    AssumedClaimStatus.SETTLED,
                    AssumedClaimStatus.COMMUTED)),
            Map.entry(AssumedClaimStatus.SETTLED, Set.of(AssumedClaimStatus.PAYMENT_PENDING)),
            Map.entry(AssumedClaimStatus.PAYMENT_PENDING, Set.of(AssumedClaimStatus.PAID)),
            Map.entry(AssumedClaimStatus.PAID, Set.of(AssumedClaimStatus.CLOSED)),
            Map.entry(AssumedClaimStatus.COMMUTED, Set.of(AssumedClaimStatus.CLOSED)),
            Map.entry(AssumedClaimStatus.CLOSED, Set.of(AssumedClaimStatus.REOPENED)),
            Map.entry(AssumedClaimStatus.REOPENED, Set.of(AssumedClaimStatus.OPEN)),
            Map.entry(AssumedClaimStatus.DISPUTED, Set.of(AssumedClaimStatus.IN_ADJUSTMENT)),
            Map.entry(AssumedClaimStatus.DENIED, Set.of(AssumedClaimStatus.CLOSED)),
            Map.entry(AssumedClaimStatus.WITHDRAWN, Set.of(AssumedClaimStatus.CLOSED))
    );

    @Test
    void lifecycleMatchesSpecSection11_5_6() {
        assertEquals(SPEC_LIFECYCLE.size(), AssumedClaimStatus.values().length);
        SPEC_LIFECYCLE.forEach((status, expected) ->
                assertEquals(expected, status.nextStatuses(), "Unexpected transitions from " + status));
    }

    @Test
    void notifiedCannotSkipStraightToPaid() {
        assertFalse(AssumedClaimStatus.NOTIFIED.canTransitionTo(AssumedClaimStatus.PAID));
        assertTrue(AssumedClaimStatus.NOTIFIED.canTransitionTo(AssumedClaimStatus.OPEN));
    }

    @Test
    void closedClaimMustBeReopenedBeforeFurtherHandling() {
        assertFalse(AssumedClaimStatus.CLOSED.canTransitionTo(AssumedClaimStatus.IN_ADJUSTMENT));
        assertTrue(AssumedClaimStatus.CLOSED.canTransitionTo(AssumedClaimStatus.REOPENED));
        assertTrue(AssumedClaimStatus.REOPENED.canTransitionTo(AssumedClaimStatus.OPEN));
    }

    @Test
    void paymentPendingOnlyMovesToPaid() {
        assertEquals(Set.of(AssumedClaimStatus.PAID), AssumedClaimStatus.PAYMENT_PENDING.nextStatuses());
        assertFalse(AssumedClaimStatus.PAYMENT_PENDING.canTransitionTo(AssumedClaimStatus.CLOSED));
    }
}
