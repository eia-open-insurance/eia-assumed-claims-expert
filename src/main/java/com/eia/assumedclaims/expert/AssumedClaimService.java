package com.eia.assumedclaims.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Claim aggregate operations and lifecycle gates. The service, not the controller, enforces the
 * invariants so an internal direct call cannot bypass them.
 *
 * <p>Every aggregate write also appends its canonical event to the transactional outbox in the
 * same transaction (SPEC 7.5.1). A rollback therefore leaves neither the claim mutation nor the
 * event behind.</p>
 */
@Service
public class AssumedClaimService {
    private static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

    private final AssumedClaimRepository claims;
    private final ReserveRepository reserves;
    private final LossAdviceRepository advices;
    private final CashCallRepository cashCalls;
    private final DisputeRepository disputes;
    private final ClaimAuditRepository audit;
    private final OutboxRepository outbox;
    private final Clock clock;

    @Autowired
    public AssumedClaimService(
            AssumedClaimRepository claims,
            ReserveRepository reserves,
            LossAdviceRepository advices,
            CashCallRepository cashCalls,
            DisputeRepository disputes,
            ClaimAuditRepository audit,
            OutboxRepository outbox) {
        this(claims, reserves, advices, cashCalls, disputes, audit, outbox, Clock.systemUTC());
    }

    AssumedClaimService(
            AssumedClaimRepository claims,
            ReserveRepository reserves,
            LossAdviceRepository advices,
            CashCallRepository cashCalls,
            DisputeRepository disputes,
            ClaimAuditRepository audit,
            OutboxRepository outbox,
            Clock clock) {
        this.claims = claims;
        this.reserves = reserves;
        this.advices = advices;
        this.cashCalls = cashCalls;
        this.disputes = disputes;
        this.audit = audit;
        this.outbox = outbox;
        this.clock = clock;
    }

    public List<AssumedClaim> list(UUID tenantId) {
        return list(tenantId, null, null);
    }

    /**
     * Claim list/search. The optional assignment filters are exact matches on
     * the current owner and let a work-queue view select a team or adjuster.
     */
    public List<AssumedClaim> list(UUID tenantId, String assignedAdjuster, String assignedTeam) {
        return claims.findAll(tenantId, assignedAdjuster, assignedTeam);
    }

    public AssumedClaim get(UUID tenantId, UUID id) {
        return requireClaim(tenantId, id);
    }

    @Transactional
    public AssumedClaim create(CreateAssumedClaimRequest request, String actor) {
        AssumedClaimRules.validateCreate(request);
        AssumedClaim claim = claims.create(request);
        LocalDate effectiveDate = request.reportDate() != null
                ? request.reportDate()
                : request.lossDate() != null ? request.lossDate() : LocalDate.now();
        reserveInitial(claim, effectiveDate, request.reserveAmount(), request.reserveCurrency());
        audit.append(request.tenantId(), claim.id(), AuditEventType.CLAIM_REGISTERED, actor,
                "Registered " + claim.assumedClaimNumber() + " with initial reserve "
                        + request.reserveAmount().toPlainString() + " " + request.reserveCurrency());
        outbox.insert(OutboxEvent.fromEnvelope(AssumedClaimEvents.registered(claim, Instant.now(clock))));
        return claim;
    }

    @Transactional
    public AssumedClaim changeStatus(UUID tenantId, UUID id, AssumedClaimStatus targetStatus, int expectedVersion, String actor) {
        AssumedClaim current = requireClaim(tenantId, id);
        if (current.version() != expectedVersion) {
            throw ApiException.versionConflict(expectedVersion);
        }
        if (targetStatus == AssumedClaimStatus.COMMUTED) {
            throw ApiException.commutationRequiresCommand();
        }
        if (targetStatus == AssumedClaimStatus.OPEN && !advices.existsAccepted(tenantId, id)) {
            throw ApiException.adviceRequiredToOpen();
        }
        if (targetStatus == AssumedClaimStatus.RESERVED
                && reserves.currentTotal(tenantId, id).compareTo(BigDecimal.ZERO) <= 0) {
            throw ApiException.reserveRequired();
        }
        if (targetStatus == AssumedClaimStatus.CLOSED) {
            validateClosure(tenantId, id);
        }
        if (!current.status().canTransitionTo(targetStatus)) {
            throw ApiException.invalidTransition(current.status(), targetStatus);
        }

        AssumedClaim updated = claims.changeStatus(tenantId, id, targetStatus, expectedVersion);
        AuditEventType eventType = targetStatus == AssumedClaimStatus.CLOSED
                ? AuditEventType.CLAIM_CLOSED
                : AuditEventType.CLAIM_STATUS_CHANGED;
        audit.append(tenantId, id, eventType, actor,
                "Status " + current.status() + " -> " + targetStatus);
        outbox.insert(OutboxEvent.fromEnvelope(
                AssumedClaimEvents.statusChanged(current, updated, Instant.now())));
        return updated;
    }

    @Transactional
    public AssumedClaim close(UUID tenantId, UUID id, String actor) {
        AssumedClaim current = requireClaim(tenantId, id);
        if (current.status() == AssumedClaimStatus.CLOSED) {
            throw ApiException.claimAlreadyClosed();
        }
        validateClosure(tenantId, id);
        if (!current.status().canTransitionTo(AssumedClaimStatus.CLOSED)) {
            throw ApiException.invalidTransition(current.status(), AssumedClaimStatus.CLOSED);
        }
        AssumedClaim closed = claims.changeStatus(tenantId, id, AssumedClaimStatus.CLOSED, current.version());
        audit.append(tenantId, id, AuditEventType.CLAIM_CLOSED, actor,
                "Status " + current.status() + " -> CLOSED");
        outbox.insert(OutboxEvent.fromEnvelope(
                AssumedClaimEvents.statusChanged(current, closed, Instant.now())));
        return closed;
    }

    /**
     * Assigns or reassigns a claim with the claim version as the optimistic
     * token. A stale version is 409. Repeating the current assignment with the
     * current version is an idempotent no-op: no version bump, no audit entry,
     * and no event. A real change bumps the claim version, appends the audit
     * entry, and writes {@code assumed.claim.assigned} to the outbox in the
     * same transaction.
     */
    @Transactional
    public AssumedClaim assign(
            UUID tenantId, UUID claimId, ClaimAssignmentRequest request, String actor) {
        AssumedClaim current = requireClaim(tenantId, claimId);
        String assignedAdjuster = TaskNoteVocab.requiredText(
                request.assignedAdjuster(), "assignedAdjuster", 128);
        String assignedTeam = TaskNoteVocab.optionalText(request.assignedTeam(), "assignedTeam", 64);
        int expectedVersion = requireExpectedVersion(request.expectedVersion());
        if (current.version() != expectedVersion) {
            throw ApiException.versionConflict(expectedVersion);
        }
        // The platform actor header is optional. When it is absent, the
        // assigned adjuster is the most useful attribution, matching Direct
        // Claims; it is never silently recorded as an unrelated user.
        String acting = TaskNoteVocab.optionalText(actor, "actor", 128);
        String assignedBy = acting == null ? assignedAdjuster : acting;
        if (assignedAdjuster.equals(current.assignedAdjuster())
                && java.util.Objects.equals(assignedTeam, current.assignedTeam())) {
            return current;
        }

        Instant now = Instant.now(clock);
        AssumedClaim updated = claims.assign(tenantId, claimId, assignedAdjuster, assignedTeam,
                        assignedBy, now, expectedVersion)
                .orElseThrow(() -> ApiException.versionConflict(expectedVersion));
        audit.append(tenantId, claimId, AuditEventType.CLAIM_ASSIGNED, assignedBy,
                assignmentDetail(current, updated));
        outbox.insert(OutboxEvent.fromEnvelope(
                AssumedClaimEvents.assigned(current, updated, assignedBy, now)));
        return updated;
    }

    public List<ClaimAuditEntry> audit(UUID tenantId, UUID id) {
        requireClaim(tenantId, id);
        return audit.findByClaim(tenantId, id);
    }

    public AssumedClaim requireClaim(UUID tenantId, UUID id) {
        return claims.findById(tenantId, id).orElseThrow(() -> ApiException.assumedClaimNotFound(id));
    }

    private void validateClosure(UUID tenantId, UUID id) {
        List<String> blockers = new java.util.ArrayList<>();
        if (disputes.existsOpen(tenantId, id)) {
            blockers.add("An open dispute must be resolved first (RNS-11).");
        }
        BigDecimal reserve = zeroIfNull(reserves.currentTotal(tenantId, id));
        if (reserve.compareTo(BigDecimal.ZERO) > 0) {
            blockers.add("A current reserve of " + reserve.toPlainString() + " must be released first (RNS-12).");
        }
        if (advices.countByStatus(tenantId, id, AdviceStatus.RECEIVED) > 0) {
            blockers.add("Every loss advice must be accepted, rejected, or superseded before closure (RNS-12).");
        }
        BigDecimal outstandingCash = zeroIfNull(cashCalls.outstandingTotal(tenantId, id));
        if (outstandingCash.compareTo(BigDecimal.ZERO) > 0) {
            blockers.add("Outstanding cash calls of " + outstandingCash.toPlainString() + " must be settled first (RNS-12).");
        }
        if (!blockers.isEmpty()) {
            throw ApiException.claimCloseBlocked(String.join(" ", blockers));
        }
    }

    private void reserveInitial(AssumedClaim claim, LocalDate effectiveDate, BigDecimal amount, String currency) {
        ReserveComponent component = ReserveComponent.CASE;
        ReserveHistoryEntry entry = new ReserveHistoryEntry(
                UuidV7.generate(),
                claim.tenantId(),
                claim.id(),
                component,
                1,
                Money.amount(amount),
                currency,
                effectiveDate,
                ReserveReason.INITIAL,
                "Initial reserve from claim registration",
                ReserveAuthority.SYSTEM,
                false,
                null,
                null,
                true,
                1,
                SYSTEM_ACTOR,
                Instant.now());
        reserves.insert(entry);
    }

    static String actor(String value) {
        return StringUtils.hasText(value) ? value.trim() : "system";
    }

    private static int requireExpectedVersion(Integer expectedVersion) {
        if (expectedVersion == null) {
            throw ApiException.validation("expectedVersion is required", List.of(
                    new FieldIssue("expectedVersion", "REQUIRED", "expectedVersion is required")));
        }
        if (expectedVersion < 0) {
            throw ApiException.validation("expectedVersion must be greater than or equal to zero",
                    List.of(new FieldIssue("expectedVersion", "NEGATIVE",
                            "expectedVersion must be greater than or equal to zero")));
        }
        return expectedVersion;
    }

    private static String assignmentDetail(AssumedClaim before, AssumedClaim after) {
        String previous = before.assignedAdjuster() == null
                ? "unassigned"
                : before.assignedAdjuster() + teamSuffix(before.assignedTeam());
        return "Assigned " + after.assumedClaimNumber() + " to " + after.assignedAdjuster()
                + teamSuffix(after.assignedTeam()) + " from " + previous;
    }

    private static String teamSuffix(String team) {
        return team == null ? "" : " (" + team + ")";
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
