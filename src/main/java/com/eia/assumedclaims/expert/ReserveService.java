package com.eia.assumedclaims.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Append-only reserve history. The current reserve is the sum of the current row per component;
 * negative amounts, stale effective dates, and missing reasons are rejected (RNS-04, RNS-05).
 */
@Service
public class ReserveService {
    public static final BigDecimal HIGH_AMOUNT_THRESHOLD = new BigDecimal("1000000.00");

    private final AssumedClaimService claimService;
    private final ReserveRepository reserves;
    private final AssumedClaimRepository claims;
    private final ClaimAuditRepository audit;
    private final OutboxRepository outbox;

    public ReserveService(
            AssumedClaimService claimService,
            ReserveRepository reserves,
            AssumedClaimRepository claims,
            ClaimAuditRepository audit,
            OutboxRepository outbox) {
        this.claimService = claimService;
        this.reserves = reserves;
        this.claims = claims;
        this.audit = audit;
        this.outbox = outbox;
    }

    public List<ReserveHistoryEntry> list(UUID tenantId, UUID claimId) {
        claimService.requireClaim(tenantId, claimId);
        return reserves.findByClaim(tenantId, claimId);
    }

    public List<ReserveHistoryEntry> current(UUID tenantId, UUID claimId) {
        claimService.requireClaim(tenantId, claimId);
        return reserves.findCurrent(tenantId, claimId);
    }

    @Transactional
    public ReserveHistoryEntry change(
            UUID tenantId,
            UUID claimId,
            ReserveChangeRequest request,
            UUID createdBy,
            String actor
    ) {
        AssumedClaim claim = claimService.requireClaim(tenantId, claimId);
        if (claim.status() == AssumedClaimStatus.NOTIFIED
                || claim.status() == AssumedClaimStatus.CLOSED
                || claim.status() == AssumedClaimStatus.COMMUTED
                || claim.status() == AssumedClaimStatus.DENIED
                || claim.status() == AssumedClaimStatus.WITHDRAWN) {
            throw ApiException.financialChangeBlocked(claim.status());
        }
        if (!request.currency().equalsIgnoreCase(claim.reserveCurrency())) {
            throw ApiException.currencyMismatch("currency", claim.reserveCurrency(), request.currency());
        }
        if (request.amount() == null || request.amount().signum() < 0) {
            throw ApiException.reserveInvalid("amount", "RESERVE_NEGATIVE",
                    "Reserve components must be non-negative (RNS-04).");
        }
        if (request.reasonCode() == null) {
            throw ApiException.reserveInvalid("reasonCode", "REASON_REQUIRED",
                    "A reserve movement requires a reason code.");
        }
        if (request.authority() == null) {
            throw ApiException.reserveInvalid("authority", "AUTHORITY_REQUIRED",
                    "A reserve movement requires an authority.");
        }
        if (request.effectiveDate() == null) {
            throw ApiException.reserveInvalid("effectiveDate", "EFFECTIVE_DATE_REQUIRED",
                    "A reserve movement requires an effective date.");
        }
        if (request.component() == null) {
            throw ApiException.reserveInvalid("component", "COMPONENT_REQUIRED",
                    "A reserve component is required.");
        }

        boolean authorityRequired = request.amount().compareTo(HIGH_AMOUNT_THRESHOLD) >= 0
                || request.component() == ReserveComponent.IBNR;
        validateAuthority(request, authorityRequired);

        reserves.findLatest(tenantId, claimId, request.component()).ifPresent(latest -> {
            if (!request.effectiveDate().isAfter(latest.effectiveDate())) {
                throw ApiException.reserveStale(request.component(), latest.effectiveDate(), request.effectiveDate());
            }
        });

        int sequence = reserves.nextSequence(tenantId, claimId, request.component());
        reserves.supersedeCurrent(tenantId, claimId, request.component());
        Instant now = Instant.now();
        ReserveHistoryEntry entry = new ReserveHistoryEntry(
                UuidV7.generate(),
                tenantId,
                claimId,
                request.component(),
                sequence,
                Money.amount(request.amount()),
                claim.reserveCurrency(),
                request.effectiveDate(),
                request.reasonCode(),
                request.reasonText(),
                request.authority(),
                authorityRequired,
                request.approvedBy(),
                request.approvedBy() == null ? null : now,
                true,
                1,
                createdBy == null ? new UUID(0L, 0L) : createdBy,
                now);
        reserves.insert(entry);
        BigDecimal total = reserves.currentTotal(tenantId, claimId);
        claims.updateReserveAmount(tenantId, claimId, total);
        audit.append(tenantId, claimId, AuditEventType.RESERVE_CHANGED, actor,
                "Reserve " + entry.component() + " seq " + entry.sequence() + " = "
                        + entry.amount().toPlainString() + " " + entry.currency()
                        + " from " + entry.effectiveDate() + " (" + entry.reasonCode() + ", "
                        + entry.authority() + ")");
        outbox.insert(OutboxEvent.fromEnvelope(AssumedClaimEvents.reserveChanged(
                claim, entry, claim.reserveAmount(), total, Instant.now())));
        return entry;
    }

    private static void validateAuthority(ReserveChangeRequest request, boolean required) {
        if (request.component() == ReserveComponent.IBNR
                && request.authority() != ReserveAuthority.ACTUARIAL
                && request.authority() != ReserveAuthority.UNDERWRITER) {
            throw ApiException.reserveAuthorityRequired(
                    "IBNR requires ACTUARIAL or UNDERWRITER authority and a named actuarial approver (RNS-05).");
        }
        if (required && (request.authority() == ReserveAuthority.SYSTEM
                || request.authority() == ReserveAuthority.ADJUSTER)) {
            throw ApiException.reserveAuthorityRequired(
                    "A reserve of " + request.amount().toPlainString()
                            + " exceeds the adjuster authority limit; senior, actuarial, or underwriting approval is required.");
        }
        if (required && request.approvedBy() == null) {
            throw ApiException.reserveAuthorityRequired(
                    "This reserve movement requires a named approver in approvedBy.");
        }
    }
}
