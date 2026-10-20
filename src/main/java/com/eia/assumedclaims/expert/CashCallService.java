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
 * Cash calls. A call may not exceed the claim's current reserve net of calls already outstanding
 * (RNS-09) and large calls require a named claims or underwriting approval.
 */
@Service
public class CashCallService {
    public static final BigDecimal APPROVAL_THRESHOLD = new BigDecimal("250000.00");

    private final AssumedClaimService claimService;
    private final CashCallRepository cashCalls;
    private final ReserveRepository reserves;
    private final AssumedClaimRepository claims;
    private final ClaimAuditRepository audit;
    private final NumberSequences numbers;
    private final OutboxRepository outbox;

    public CashCallService(
            AssumedClaimService claimService,
            CashCallRepository cashCalls,
            ReserveRepository reserves,
            AssumedClaimRepository claims,
            ClaimAuditRepository audit,
            NumberSequences numbers,
            OutboxRepository outbox) {
        this.claimService = claimService;
        this.cashCalls = cashCalls;
        this.reserves = reserves;
        this.claims = claims;
        this.audit = audit;
        this.numbers = numbers;
        this.outbox = outbox;
    }

    public List<CashCall> list(UUID tenantId, UUID claimId) {
        claimService.requireClaim(tenantId, claimId);
        return cashCalls.findByClaim(tenantId, claimId);
    }

    @Transactional
    public CashCall create(UUID tenantId, UUID claimId, RequestCashCallRequest request, String actor) {
        AssumedClaim claim = claimService.requireClaim(tenantId, claimId);
        requireOpen(claim);
        if (!request.currency().equalsIgnoreCase(claim.reserveCurrency())) {
            throw ApiException.currencyMismatch("currency", claim.reserveCurrency(), request.currency());
        }
        if (request.requestedAmount() == null || request.requestedAmount().signum() <= 0) {
            throw ApiException.validation("A cash call must request a positive amount.", List.of(
                    new FieldIssue("requestedAmount", "CASH_CALL_AMOUNT", "requestedAmount must be greater than zero")));
        }
        LocalDate requestedAt = request.requestedAt() == null ? LocalDate.now() : request.requestedAt();
        if (request.dueDate() != null && request.dueDate().isBefore(requestedAt)) {
            throw ApiException.validation("The cash-call due date precedes the request date.", List.of(
                    new FieldIssue("dueDate", "DUE_DATE_BEFORE_REQUEST", "dueDate must be on or after requestedAt")));
        }

        BigDecimal currentReserve = reserves.currentTotal(tenantId, claimId);
        BigDecimal outstanding = cashCalls.outstandingTotal(tenantId, claimId);
        BigDecimal available = currentReserve.subtract(outstanding);
        if (available.signum() < 0) {
            available = BigDecimal.ZERO;
        }
        if (request.requestedAmount().compareTo(available) > 0) {
            throw ApiException.cashCallExceedsReserve(
                    "The cash call of " + request.requestedAmount().toPlainString()
                            + " " + claim.reserveCurrency() + " exceeds the available reserve of "
                            + available.toPlainString() + " " + claim.reserveCurrency() + " (RNS-09).");
        }

        boolean approvalRequired = request.requestedAmount().compareTo(APPROVAL_THRESHOLD) > 0;
        if (approvalRequired && request.approvedBy() == null) {
            throw ApiException.cashCallApprovalRequired(
                    "A cash call above " + APPROVAL_THRESHOLD.toPlainString()
                            + " requires a named CLAIMS or UNDERWRITER approver.");
        }
        if (approvalRequired && request.approverRole() != ApproverRole.CLAIMS
                && request.approverRole() != ApproverRole.UNDERWRITER) {
            throw ApiException.cashCallApprovalRequired(
                    "A cash call above " + APPROVAL_THRESHOLD.toPlainString()
                            + " requires a CLAIMS or UNDERWRITER approver role.");
        }

        Instant now = Instant.now();
        boolean approved = request.approvedBy() != null;
        CashCall call = new CashCall(
                UuidV7.generate(),
                tenantId,
                claimId,
                numbers.nextCashCallNumber(),
                Money.amount(request.requestedAmount()),
                claim.reserveCurrency(),
                requestedAt,
                request.dueDate(),
                approved ? CashCallStatus.APPROVED : CashCallStatus.SUBMITTED,
                request.reason(),
                request.reasonText(),
                request.approvedBy(),
                request.approverRole(),
                approved ? now : null,
                approved ? "APPROVED" : approvalRequired ? "PENDING" : "NOT_REQUIRED",
                request.paymentId(),
                1,
                now,
                now);
        cashCalls.insert(call);
        claims.touch(tenantId, claimId);
        audit.append(tenantId, claimId, AuditEventType.CASH_CALL_REQUESTED, actor,
                "Cash call " + call.requestNumber() + " for " + call.requestedAmount().toPlainString()
                        + " " + call.currency() + " (" + call.status() + ")");
        outbox.insert(OutboxEvent.fromEnvelope(
                AssumedClaimEvents.cashCallRequested(claim, call, Instant.now())));
        return call;
    }

    private static void requireOpen(AssumedClaim claim) {
        if (claim.status() == AssumedClaimStatus.NOTIFIED
                || claim.status() == AssumedClaimStatus.CLOSED
                || claim.status() == AssumedClaimStatus.COMMUTED
                || claim.status() == AssumedClaimStatus.DENIED
                || claim.status() == AssumedClaimStatus.WITHDRAWN) {
            throw ApiException.financialChangeBlocked(claim.status());
        }
    }
}
