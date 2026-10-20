package com.eia.assumedclaims.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Recoveries. Total non-denied recoveries may not exceed the ceded advised/paid loss (RNS-10).
 */
@Service
public class RecoveryService {
    private final AssumedClaimService claimService;
    private final RecoveryRepository recoveries;
    private final LossAdviceRepository advices;
    private final AssumedClaimRepository claims;
    private final ClaimAuditRepository audit;
    private final OutboxRepository outbox;

    public RecoveryService(
            AssumedClaimService claimService,
            RecoveryRepository recoveries,
            LossAdviceRepository advices,
            AssumedClaimRepository claims,
            ClaimAuditRepository audit,
            OutboxRepository outbox) {
        this.claimService = claimService;
        this.recoveries = recoveries;
        this.advices = advices;
        this.claims = claims;
        this.audit = audit;
        this.outbox = outbox;
    }

    public List<Recovery> list(UUID tenantId, UUID claimId) {
        claimService.requireClaim(tenantId, claimId);
        return recoveries.findByClaim(tenantId, claimId);
    }

    @Transactional
    public Recovery create(UUID tenantId, UUID claimId, RecordRecoveryRequest request, String actor) {
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
        if (request.amount() == null || request.amount().signum() <= 0) {
            throw ApiException.validation("A recovery must be a positive amount.", List.of(
                    new FieldIssue("amount", "RECOVERY_AMOUNT", "amount must be greater than zero")));
        }

        BigDecimal advisedLoss = advices.advisedLoss(tenantId, claimId);
        BigDecimal consumed = recoveries.consumedCapacity(tenantId, claimId);
        BigDecimal available = advisedLoss.subtract(consumed);
        if (available.signum() < 0) {
            available = BigDecimal.ZERO;
        }
        if (request.amount().compareTo(available) > 0) {
            throw ApiException.recoveryExceedsAdvisedLoss(
                    "The recovery of " + request.amount().toPlainString() + " " + claim.reserveCurrency()
                            + " exceeds the remaining advised/paid loss of " + available.toPlainString()
                            + " (advised loss " + advisedLoss.toPlainString() + ", already recovered "
                            + consumed.toPlainString() + "; RNS-10).");
        }

        Instant now = Instant.now();
        LocalDate receivedDate = request.receivedDate() == null ? LocalDate.now() : request.receivedDate();
        Recovery recovery = new Recovery(
                UuidV7.generate(),
                tenantId,
                claimId,
                request.recoveryType(),
                Money.amount(request.amount()),
                claim.reserveCurrency(),
                receivedDate,
                request.status() == null ? RecoveryStatus.RECEIVED : request.status(),
                request.sourcePartyId(),
                request.sourceContractId(),
                Money.amount(request.expectedAmount()),
                request.paymentId(),
                1,
                now,
                now);
        recoveries.insert(recovery);
        claims.touch(tenantId, claimId);
        audit.append(tenantId, claimId, AuditEventType.RECOVERY_RECORDED, actor,
                "Recovery " + recovery.recoveryType() + " of " + recovery.amount().toPlainString()
                        + " " + recovery.currency() + " (" + recovery.status() + ")");
        outbox.insert(OutboxEvent.fromEnvelope(
                AssumedClaimEvents.recoveryRecorded(claim, recovery, Instant.now())));
        return recovery;
    }
}
