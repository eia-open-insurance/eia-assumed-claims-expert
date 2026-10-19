package com.eia.assumedclaims.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Persisted loss advice (SPEC §11.5.4). Amounts are decimal strings in JSON (SPEC §10.3) and the
 * advice is unique per claim by {@code adviceNumber} (RNS-02).
 */
public record LossAdvice(
        UUID id,
        UUID tenantId,
        UUID assumedClaimId,
        String adviceNumber,
        String cedentClaimNumber,
        AdviceType adviceType,
        LocalDate adviceDate,
        LocalDate receivedDate,
        LocalDate periodFrom,
        LocalDate periodTo,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal reportedAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal paidAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal outstandingAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal expenseAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal reserveAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal recoveryAmount,
        String currency,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cessionPct,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cededAmount,
        boolean layerAttachmentMet,
        AdviceStatus status,
        String rejectionReason,
        UUID sourceDocumentId,
        UUID bordereauId,
        Integer bordereauLineNumber,
        AdviceSource source,
        int version,
        Instant postedAt,
        String notes,
        Instant createdAt,
        Instant updatedAt
) {}
