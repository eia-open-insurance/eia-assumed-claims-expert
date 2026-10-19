package com.eia.assumedclaims.expert;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Disputes. An open dispute blocks commutation and closure (RNS-11); resolution always requires a
 * written resolution and keeps the original contest amount.
 */
@Service
public class DisputeService {
    private final AssumedClaimService claimService;
    private final DisputeRepository disputes;
    private final AssumedClaimRepository claims;
    private final ClaimAuditRepository audit;
    private final NumberSequences numbers;

    public DisputeService(
            AssumedClaimService claimService,
            DisputeRepository disputes,
            AssumedClaimRepository claims,
            ClaimAuditRepository audit,
            NumberSequences numbers) {
        this.claimService = claimService;
        this.disputes = disputes;
        this.claims = claims;
        this.audit = audit;
        this.numbers = numbers;
    }

    public List<Dispute> list(UUID tenantId, UUID claimId) {
        claimService.requireClaim(tenantId, claimId);
        return disputes.findByClaim(tenantId, claimId);
    }

    @Transactional
    public Dispute raise(UUID tenantId, UUID claimId, RaiseDisputeRequest request, String actor) {
        AssumedClaim claim = claimService.requireClaim(tenantId, claimId);
        if (claim.status() == AssumedClaimStatus.NOTIFIED
                || claim.status() == AssumedClaimStatus.CLOSED
                || claim.status() == AssumedClaimStatus.COMMUTED
                || claim.status() == AssumedClaimStatus.DENIED
                || claim.status() == AssumedClaimStatus.WITHDRAWN) {
            throw ApiException.businessRule("EIA-RNS-7603", "Dispute cannot be raised",
                    "A dispute cannot be raised while the claim is " + claim.status() + ".");
        }
        if (!request.currency().equalsIgnoreCase(claim.reserveCurrency())) {
            throw ApiException.currencyMismatch("currency", claim.reserveCurrency(), request.currency());
        }
        if (request.contestedAmount() == null || request.contestedAmount().signum() < 0) {
            throw ApiException.validation("The contested amount must be non-negative.", List.of(
                    new FieldIssue("contestedAmount", "CONTESTED_AMOUNT", "must not be negative")));
        }
        Instant now = Instant.now();
        LocalDate raisedAt = request.raisedAt() == null ? LocalDate.now() : request.raisedAt();
        Dispute dispute = new Dispute(
                UuidV7.generate(),
                tenantId,
                claimId,
                numbers.nextDisputeNumber(),
                request.disputeType(),
                request.reason().trim(),
                Money.amount(request.contestedAmount()),
                claim.reserveCurrency(),
                DisputeStatus.OPEN,
                null,
                raisedAt,
                null,
                request.arbitrationFlag(),
                request.raisedByPartyId(),
                1,
                now,
                now);
        disputes.insert(dispute);
        claims.touch(tenantId, claimId);
        audit.append(tenantId, claimId, AuditEventType.DISPUTE_RAISED, actor,
                "Dispute " + dispute.disputeNumber() + " (" + dispute.disputeType() + ") for "
                        + dispute.contestedAmount().toPlainString() + " " + dispute.currency());
        return dispute;
    }

    @Transactional
    public Dispute resolve(
            UUID tenantId,
            UUID claimId,
            UUID disputeId,
            ResolveDisputeRequest request,
            String actor
    ) {
        claimService.requireClaim(tenantId, claimId);
        Dispute dispute = disputes.findById(tenantId, claimId, disputeId)
                .orElseThrow(() -> ApiException.disputeNotFound(disputeId));
        if (!dispute.status().isOpen()) {
            throw ApiException.disputeInvalid(dispute.status());
        }
        if (request.status() != DisputeStatus.RESOLVED && request.status() != DisputeStatus.CLOSED) {
            throw ApiException.validation("A dispute resolution status must be RESOLVED or CLOSED.", List.of(
                    new FieldIssue("status", "DISPUTE_RESOLUTION_STATUS",
                            "must be RESOLVED or CLOSED")));
        }
        if (request.resolution() == null || request.resolution().isBlank()) {
            throw ApiException.validation("A dispute resolution requires a written resolution.", List.of(
                    new FieldIssue("resolution", "RESOLUTION_REQUIRED", "resolution is required")));
        }
        LocalDate resolvedAt = request.resolvedAt() == null ? LocalDate.now() : request.resolvedAt();
        if (resolvedAt.isBefore(dispute.raisedAt())) {
            throw ApiException.validation("The resolution date precedes the raised date.", List.of(
                    new FieldIssue("resolvedAt", "RESOLUTION_BEFORE_RAISED",
                            "resolvedAt must be on or after raisedAt")));
        }
        Dispute resolved = disputes.resolve(
                tenantId, claimId, disputeId, request.status(), request.resolution().trim(), resolvedAt);
        claims.touch(tenantId, claimId);
        audit.append(tenantId, claimId, AuditEventType.DISPUTE_RESOLVED, actor,
                "Dispute " + resolved.disputeNumber() + " -> " + resolved.status()
                        + ": " + resolved.resolution());
        return resolved;
    }
}
