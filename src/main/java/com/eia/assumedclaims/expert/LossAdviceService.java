package com.eia.assumedclaims.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class LossAdviceService {
    private final AssumedClaimService claimService;
    private final LossAdviceRepository advices;
    private final AssumedClaimRepository claims;
    private final ClaimAuditRepository audit;
    private final OutboxRepository outbox;

    public LossAdviceService(
            AssumedClaimService claimService,
            LossAdviceRepository advices,
            AssumedClaimRepository claims,
            ClaimAuditRepository audit,
            OutboxRepository outbox) {
        this.claimService = claimService;
        this.advices = advices;
        this.claims = claims;
        this.audit = audit;
        this.outbox = outbox;
    }

    public List<LossAdvice> list(UUID tenantId, UUID claimId) {
        claimService.requireClaim(tenantId, claimId);
        return advices.findByClaim(tenantId, claimId);
    }

    @Transactional
    public LossAdvice record(UUID tenantId, UUID claimId, RecordAdviceRequest request, String actor) {
        return record(tenantId, claimId, request, AdviceSource.MANUAL, null, null, actor);
    }

    @Transactional
    public LossAdvice record(
            UUID tenantId,
            UUID claimId,
            RecordAdviceRequest request,
            AdviceSource source,
            UUID bordereauId,
            Integer bordereauLineNumber,
            String actor
    ) {
        AssumedClaim claim = claimService.requireClaim(tenantId, claimId);
        if (claim.status() == AssumedClaimStatus.CLOSED || claim.status() == AssumedClaimStatus.COMMUTED) {
            throw ApiException.financialChangeBlocked(claim.status());
        }
        validate(claim, request);
        if (advices.existsByAdviceNumber(tenantId, claimId, request.adviceNumber().trim())) {
            throw ApiException.duplicateAdvice(request.adviceNumber());
        }

        Instant now = Instant.now();
        LocalDate receivedDate = request.receivedDate() == null ? request.adviceDate() : request.receivedDate();
        LossAdvice advice = new LossAdvice(
                UuidV7.generate(),
                tenantId,
                claimId,
                request.adviceNumber().trim(),
                StringUtils.hasText(request.cedentClaimNumber())
                        ? request.cedentClaimNumber().trim()
                        : claim.cedentClaimNumber(),
                request.adviceType(),
                request.adviceDate(),
                receivedDate,
                request.periodFrom(),
                request.periodTo(),
                Money.amount(request.reportedAmount()),
                Money.amount(request.paidAmount()),
                Money.amount(request.outstandingAmount()),
                Money.amount(request.expenseAmount()),
                Money.amount(request.reserveAmount()),
                Money.amount(request.recoveryAmount()),                request.currency(),
                request.cessionPct(),
                Money.amount(request.cededAmount()),
                Boolean.TRUE.equals(request.layerAttachmentMet()),
                AdviceStatus.RECEIVED,
                null,
                request.sourceDocumentId(),
                bordereauId,
                bordereauLineNumber,
                source,
                1,
                null,
                request.notes(),
                now,
                now);
        advices.insert(advice);
        claims.touch(tenantId, claimId);
        audit.append(tenantId, claimId, AuditEventType.LOSS_ADVICE_RECORDED, actor,
                "Advice " + advice.adviceNumber() + " (" + advice.adviceType() + ") reported "
                        + advice.reportedAmount().toPlainString() + " " + advice.currency());
        return advice;
    }

    @Transactional
    public LossAdvice accept(UUID tenantId, UUID claimId, UUID adviceId, AcceptAdviceRequest request, String actor) {
        AssumedClaim claim = claimService.requireClaim(tenantId, claimId);
        LossAdvice advice = advices.findById(tenantId, claimId, adviceId)
                .orElseThrow(() -> ApiException.adviceNotFound(adviceId));
        if (advice.status() == AdviceStatus.POSTED) {
            return advice;
        }
        if (advice.status() != AdviceStatus.RECEIVED) {
            throw ApiException.adviceNotAccepted(advice.status());
        }
        LossAdvice accepted = advices.accept(tenantId, claimId, adviceId, Instant.now());
        claims.touch(tenantId, claimId);
        String note = request != null && StringUtils.hasText(request.note()) ? " " + request.note().trim() : "";
        audit.append(tenantId, claimId, AuditEventType.LOSS_ADVICE_ACCEPTED, actor,
                "Accepted advice " + advice.adviceNumber() + note);
        outbox.insert(OutboxEvent.fromEnvelope(
                AssumedClaimEvents.lossAdvicePosted(claim, accepted, Instant.now())));
        return accepted;
    }

    private static void validate(AssumedClaim claim, RecordAdviceRequest request) {
        List<FieldIssue> issues = new ArrayList<>();
        if (!request.currency().equalsIgnoreCase(claim.reserveCurrency())) {
            throw ApiException.currencyMismatch("currency", claim.reserveCurrency(), request.currency());
        }
        if (claim.lossDate() != null && request.adviceDate().isBefore(claim.lossDate())) {
            issues.add(new FieldIssue("adviceDate", "ADVICE_BEFORE_LOSS",
                    "adviceDate must be on or after the claim lossDate"));
        }
        LocalDate received = request.receivedDate();
        if (received != null && received.isBefore(request.adviceDate())) {
            issues.add(new FieldIssue("receivedDate", "RECEIVED_BEFORE_ADVICE",
                    "receivedDate must be on or after adviceDate"));
        }
        if (request.periodFrom() != null && request.periodTo() != null
                && request.periodTo().isBefore(request.periodFrom())) {
            issues.add(new FieldIssue("periodTo", "PERIOD_ORDER",
                    "periodTo must be on or after periodFrom"));
        }
        if (request.reportedAmount() == null) {
            issues.add(new FieldIssue("reportedAmount", "REPORTED_AMOUNT_REQUIRED",
                    "reportedAmount is required"));
        }
        if (!issues.isEmpty()) {
            throw ApiException.validation("The loss advice violates a business invariant.", issues);
        }
    }
}
