package com.eia.assumedclaims.expert;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Bordereau batch ingest. A batch is idempotent by reference and content hash; advice lines are
 * idempotent by advice number. Duplicate lines are reported, never silently overwritten (RNS-02,
 * INT-01).
 */
@Service
public class BordereauService {
    private final AssumedClaimService claimService;
    private final LossAdviceService adviceService;
    private final LossAdviceRepository advices;
    private final BordereauRepository bordereaux;
    private final ClaimAuditRepository audit;

    public BordereauService(
            AssumedClaimService claimService,
            LossAdviceService adviceService,
            LossAdviceRepository advices,
            BordereauRepository bordereaux,
            ClaimAuditRepository audit) {
        this.claimService = claimService;
        this.adviceService = adviceService;
        this.advices = advices;
        this.bordereaux = bordereaux;
        this.audit = audit;
    }

    public List<BordereauBatch> list(UUID tenantId, UUID claimId) {
        claimService.requireClaim(tenantId, claimId);
        return bordereaux.findByClaim(tenantId, claimId);
    }

    @Transactional
    public BordereauIngestResult ingest(UUID tenantId, UUID claimId, IngestBordereauRequest request, String actor) {
        AssumedClaim claim = claimService.requireClaim(tenantId, claimId);
        if (claim.status() == AssumedClaimStatus.CLOSED || claim.status() == AssumedClaimStatus.COMMUTED) {
            throw ApiException.financialChangeBlocked(claim.status());
        }
        String reference = request.bordereauReference().trim();
        if (!request.currency().equalsIgnoreCase(claim.reserveCurrency())) {
            throw ApiException.currencyMismatch("currency", claim.reserveCurrency(), request.currency());
        }
        if (StringUtils.hasText(request.cedentClaimNumber())
                && StringUtils.hasText(claim.cedentClaimNumber())
                && !request.cedentClaimNumber().trim().equals(claim.cedentClaimNumber())) {
            throw ApiException.validation("The bordereau cedent claim number does not match the claim.", List.of(
                    new FieldIssue("cedentClaimNumber", "CEDENT_CLAIM_MISMATCH",
                            "must equal " + claim.cedentClaimNumber())));
        }
        if (claim.lossDate() != null && request.adviceDate().isBefore(claim.lossDate())) {
            throw ApiException.validation("The bordereau advice date precedes the claim loss date.", List.of(
                    new FieldIssue("adviceDate", "ADVICE_BEFORE_LOSS",
                            "adviceDate must be on or after the claim lossDate")));
        }

        String hash = payloadHash(claimId, request);
        BordereauBatch existing = bordereaux.findByReference(tenantId, reference).orElse(null);
        if (existing != null) {
            if (!existing.assumedClaimId().equals(claimId) || !existing.payloadHash().equals(hash)) {
                throw ApiException.bordereauDuplicate(reference);
            }
            List<BordereauIngestResult.LineResult> replayLines = advices.findByBordereau(tenantId, existing.id()).stream()
                    .map(line -> new BordereauIngestResult.LineResult(
                            line.bordereauLineNumber() == null ? 0 : line.bordereauLineNumber(),
                            line.adviceNumber(),
                            BordereauLineStatus.ACCEPTED,
                            null,
                            line.id(),
                            line.reportedAmount()))
                    .toList();
            return new BordereauIngestResult(existing, replayLines, true);
        }

        Instant now = Instant.now();
        UUID batchId = UuidV7.generate();
        String cedentClaimNumber = StringUtils.hasText(request.cedentClaimNumber())
                ? request.cedentClaimNumber().trim()
                : claim.cedentClaimNumber();
        BordereauBatch pending = new BordereauBatch(
                batchId,
                tenantId,
                claimId,
                reference,
                cedentClaimNumber,
                request.adviceDate(),
                BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
                request.currency(),
                BordereauStatus.PROCESSED,
                AdviceSource.BORDEREAU,
                1,
                0,
                0,
                0,
                0,
                hash,
                now,
                now,
                now);
        bordereaux.insertPending(pending);

        Set<String> seen = new HashSet<>();
        List<BordereauIngestResult.LineResult> results = new ArrayList<>();
        int accepted = 0;
        int duplicate = 0;
        int rejected = 0;
        BigDecimal reportedTotal = BigDecimal.ZERO;

        List<IngestBordereauRequest.BordereauLine> lines = request.lines();
        for (int index = 0; index < lines.size(); index++) {
            int lineNumber = index + 1;
            IngestBordereauRequest.BordereauLine line = lines.get(index);
            String adviceNumber = line.adviceNumber() == null ? "" : line.adviceNumber().trim();
            String rejection = validateLine(claim, request, line, adviceNumber);
            if (rejection != null) {
                rejected++;
                results.add(new BordereauIngestResult.LineResult(
                        lineNumber, adviceNumber, BordereauLineStatus.REJECTED, rejection, null, line.reportedAmount()));
                continue;
            }
            if (!seen.add(adviceNumber) || advices.existsByAdviceNumber(tenantId, claimId, adviceNumber)) {
                duplicate++;
                results.add(new BordereauIngestResult.LineResult(
                        lineNumber, adviceNumber, BordereauLineStatus.DUPLICATE,
                        "Advice number already exists for this claim", null, line.reportedAmount()));
                continue;
            }

            LocalDate adviceDate = line.adviceDate() == null ? request.adviceDate() : line.adviceDate();
            AdviceType adviceType = line.adviceType() == null ? AdviceType.UPDATE : line.adviceType();
            RecordAdviceRequest adviceRequest = new RecordAdviceRequest(
                    adviceNumber,
                    adviceType,
                    adviceDate,
                    adviceDate,
                    null,
                    null,
                    line.reportedAmount(),
                    line.paidAmount(),
                    line.outstandingAmount(),
                    line.expenseAmount(),
                    null,
                    null,
                    request.currency(),
                    null,
                    null,
                    null,
                    null,
                    cedentClaimNumber,
                    "Bordereau " + reference + " line " + lineNumber);
            try {
                LossAdvice advice = adviceService.record(
                        tenantId, claimId, adviceRequest, AdviceSource.BORDEREAU, batchId, lineNumber, actor);
                accepted++;
                reportedTotal = reportedTotal.add(advice.reportedAmount());
                results.add(new BordereauIngestResult.LineResult(
                        lineNumber, adviceNumber, BordereauLineStatus.ACCEPTED, null,
                        advice.id(), advice.reportedAmount()));
            } catch (ApiException exception) {
                if ("EIA-RNS-7101".equals(exception.code())) {
                    duplicate++;
                    results.add(new BordereauIngestResult.LineResult(
                            lineNumber, adviceNumber, BordereauLineStatus.DUPLICATE,
                            "Advice number already exists for this claim", null, line.reportedAmount()));
                } else {
                    throw exception;
                }
            }
        }

        BordereauStatus status;
        if (accepted == 0 && duplicate > 0 && rejected == 0) {
            status = BordereauStatus.DUPLICATE;
        } else if (accepted == 0) {
            status = BordereauStatus.REJECTED;
        } else if (duplicate > 0 || rejected > 0) {
            status = BordereauStatus.PARTIAL;
        } else {
            status = BordereauStatus.PROCESSED;
        }

        BordereauBatch completed = bordereaux.complete(
                tenantId,
                batchId,
                reportedTotal.setScale(4, RoundingMode.HALF_UP),
                status,
                lines.size(),
                accepted,
                duplicate,
                rejected,
                Instant.now());
        audit.append(tenantId, claimId, AuditEventType.BORDEREAU_INGESTED, actor,
                "Bordereau " + reference + " accepted=" + accepted + " duplicate=" + duplicate
                        + " rejected=" + rejected);
        return new BordereauIngestResult(completed, results, false);
    }

    private static String validateLine(
            AssumedClaim claim,
            IngestBordereauRequest batch,
            IngestBordereauRequest.BordereauLine line,
            String adviceNumber
    ) {
        if (!StringUtils.hasText(adviceNumber)) {
            return "adviceNumber is required";
        }
        if (line.reportedAmount() == null) {
            return "reportedAmount is required";
        }
        if (line.reportedAmount().signum() < 0) {
            return "reportedAmount must not be negative";
        }
        if (line.paidAmount() != null && line.paidAmount().signum() < 0) {
            return "paidAmount must not be negative";
        }
        if (line.outstandingAmount() != null && line.outstandingAmount().signum() < 0) {
            return "outstandingAmount must not be negative";
        }
        if (line.expenseAmount() != null && line.expenseAmount().signum() < 0) {
            return "expenseAmount must not be negative";
        }
        LocalDate date = line.adviceDate() == null ? batch.adviceDate() : line.adviceDate();
        if (claim.lossDate() != null && date.isBefore(claim.lossDate())) {
            return "adviceDate precedes the claim lossDate";
        }
        return null;
    }

    private static String payloadHash(UUID claimId, IngestBordereauRequest request) {
        StringBuilder canonical = new StringBuilder()
                .append(claimId).append('|')
                .append(request.bordereauReference().trim()).append('|')
                .append(request.adviceDate()).append('|')
                .append(request.currency()).append('|')
                .append(request.cedentClaimNumber() == null ? "" : request.cedentClaimNumber().trim()).append('|');
        for (IngestBordereauRequest.BordereauLine line : request.lines()) {
            canonical.append(line.adviceNumber() == null ? "" : line.adviceNumber().trim()).append('|')
                    .append(line.adviceDate()).append('|')
                    .append(money(line.reportedAmount())).append('|')
                    .append(money(line.paidAmount())).append('|')
                    .append(money(line.outstandingAmount())).append('|')
                    .append(money(line.expenseAmount())).append('|')
                    .append(line.adviceType()).append(';');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String money(BigDecimal value) {
        return value == null ? "" : value.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }
}
