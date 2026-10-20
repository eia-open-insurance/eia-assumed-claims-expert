package com.eia.assumedclaims.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Commutation. Execution requires a named underwriter or claims approver, a signed release, no
 * open dispute, and no unresolved advice; it releases reserves and closes the claim (RNS-08, RNS-11).
 */
@Service
public class CommutationService {
    private final AssumedClaimService claimService;
    private final CommutationRepository commutations;
    private final ReserveRepository reserves;
    private final DisputeRepository disputes;
    private final LossAdviceRepository advices;
    private final AssumedClaimRepository claims;
    private final ClaimAuditRepository audit;
    private final NumberSequences numbers;
    private final OutboxRepository outbox;

    public CommutationService(
            AssumedClaimService claimService,
            CommutationRepository commutations,
            ReserveRepository reserves,
            DisputeRepository disputes,
            LossAdviceRepository advices,
            AssumedClaimRepository claims,
            ClaimAuditRepository audit,
            NumberSequences numbers,
            OutboxRepository outbox) {
        this.claimService = claimService;
        this.commutations = commutations;
        this.reserves = reserves;
        this.disputes = disputes;
        this.advices = advices;
        this.claims = claims;
        this.audit = audit;
        this.numbers = numbers;
        this.outbox = outbox;
    }

    public Commutation get(UUID tenantId, UUID claimId) {
        claimService.requireClaim(tenantId, claimId);
        return commutations.findByClaim(tenantId, claimId)
                .orElseThrow(ApiException::commutationNotFound);
    }

    @Transactional
    public Commutation execute(UUID tenantId, UUID claimId, CommuteClaimRequest request, String actor) {
        AssumedClaim claim = claimService.requireClaim(tenantId, claimId);
        if (claim.status() == AssumedClaimStatus.CLOSED || claim.status() == AssumedClaimStatus.COMMUTED) {
            throw ApiException.claimAlreadyClosed();
        }
        if (commutations.findByClaim(tenantId, claimId).isPresent()) {
            throw ApiException.commutationBlocked("A commutation already exists for this claim.");
        }
        if (claim.status() != AssumedClaimStatus.OPEN
                && claim.status() != AssumedClaimStatus.RESERVED
                && claim.status() != AssumedClaimStatus.IN_ADJUSTMENT) {
            throw ApiException.commutationBlocked(
                    "A claim in status " + claim.status() + " cannot be commuted.");
        }
        if (!request.currency().equalsIgnoreCase(claim.reserveCurrency())) {
            throw ApiException.currencyMismatch("currency", claim.reserveCurrency(), request.currency());
        }
        if (request.offerAmount() == null || request.acceptedAmount() == null
                || request.offerAmount().signum() < 0 || request.acceptedAmount().signum() < 0) {
            throw ApiException.validation("Commutation amounts must be non-negative.", java.util.List.of(
                    new FieldIssue("acceptedAmount", "COMMUTATION_AMOUNT", "amounts must be non-negative")));
        }
        if (request.approvedBy() == null
                || (request.approverRole() != ApproverRole.UNDERWRITER
                && request.approverRole() != ApproverRole.CLAIMS)) {
            throw ApiException.commutationApprovalRequired();
        }
        if (request.releaseDocumentId() == null) {
            throw ApiException.releaseDocumentRequired();
        }
        if (disputes.existsOpen(tenantId, claimId)) {
            throw ApiException.commutationBlocked(
                    "An open dispute blocks commutation until it is resolved (RNS-11).");
        }
        if (advices.countByStatus(tenantId, claimId, AdviceStatus.RECEIVED) > 0) {
            throw ApiException.commutationBlocked(
                    "Every loss advice must be accepted, rejected, or superseded before commutation (RNS-08).");
        }

        Instant now = Instant.now();
        BigDecimal accepted = request.acceptedAmount();
        BigDecimal offer = request.offerAmount();
        BigDecimal savings = request.savingsAmount() != null
                ? request.savingsAmount()
                : offer.subtract(accepted).max(BigDecimal.ZERO);
        Commutation commutation = new Commutation(
                UuidV7.generate(),
                tenantId,
                claimId,
                numbers.nextCommutationNumber(),
                request.commutationType() == null ? CommutationType.FULL : request.commutationType(),
                Money.amount(offer),
                Money.amount(accepted),
                claim.reserveCurrency(),
                request.effectiveDate(),
                request.valuationDate(),
                request.basis() == null ? CommutationBasis.NEGOTIATED : request.basis(),
                request.releaseDocumentId(),
                CommutationStatus.EXECUTED,
                request.approvedBy(),
                request.approverRole(),
                now,
                now,
                Money.amount(savings),
                1,
                now,
                now);
        commutations.insert(commutation);
        reserves.deactivateAll(tenantId, claimId);
        AssumedClaim closed = claims.closeAfterCommutation(tenantId, claimId);
        audit.append(tenantId, claimId, AuditEventType.COMMUTATION_EXECUTED, actor,
                "Commutation " + commutation.commutationNumber() + " accepted "
                        + accepted.toPlainString() + " " + commutation.currency()
                        + "; claim " + closed.status() + " (release " + request.releaseDocumentId() + ")");
        outbox.insert(OutboxEvent.fromEnvelope(AssumedClaimEvents.commuted(
                closed, commutation, claim.reserveAmount(), Instant.now())));
        return commutation;
    }
}
