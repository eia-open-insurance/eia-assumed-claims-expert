package com.eia.assumedclaims.expert;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Assumed claim statuses and the permitted lifecycle transitions from SPEC §11.5.6.
 *
 * <p>The expert persists the lifecycle decision made by the domain service, but it also applies
 * the same guard so that a direct internal call cannot bypass the state machine.</p>
 */
public enum AssumedClaimStatus {
    NOTIFIED,
    OPEN,
    RESERVED,
    IN_ADJUSTMENT,
    SETTLED,
    PAYMENT_PENDING,
    PAID,
    CLOSED,
    DENIED,
    COMMUTED,
    REOPENED,
    DISPUTED,
    WITHDRAWN;

    private static final Map<AssumedClaimStatus, Set<AssumedClaimStatus>> TRANSITIONS =
            new EnumMap<>(AssumedClaimStatus.class);

    static {
        TRANSITIONS.put(NOTIFIED, Set.of(OPEN));
        TRANSITIONS.put(OPEN, Set.of(RESERVED, DENIED, WITHDRAWN, DISPUTED));
        TRANSITIONS.put(RESERVED, Set.of(IN_ADJUSTMENT, COMMUTED));
        TRANSITIONS.put(IN_ADJUSTMENT, Set.of(SETTLED, COMMUTED));
        TRANSITIONS.put(SETTLED, Set.of(PAYMENT_PENDING));
        TRANSITIONS.put(PAYMENT_PENDING, Set.of(PAID));
        TRANSITIONS.put(PAID, Set.of(CLOSED));
        TRANSITIONS.put(COMMUTED, Set.of(CLOSED));
        TRANSITIONS.put(CLOSED, Set.of(REOPENED));
        TRANSITIONS.put(REOPENED, Set.of(OPEN));
        TRANSITIONS.put(DISPUTED, Set.of(IN_ADJUSTMENT));
        TRANSITIONS.put(DENIED, Set.of(CLOSED));
        TRANSITIONS.put(WITHDRAWN, Set.of(CLOSED));
    }

    public boolean canTransitionTo(AssumedClaimStatus target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    public Set<AssumedClaimStatus> nextStatuses() {
        return TRANSITIONS.getOrDefault(this, Set.of());
    }
}
