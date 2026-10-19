package com.eia.assumedclaims.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A cash call request (SPEC §11.5.4). It may never exceed the claim's current reserve (RNS-09). */
public record CashCall(
        UUID id,
        UUID tenantId,
        UUID assumedClaimId,
        String requestNumber,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal requestedAmount,
        String currency,
        LocalDate requestedAt,
        LocalDate dueDate,
        CashCallStatus status,
        CashCallReason reason,
        String reasonText,
        UUID approvedBy,
        ApproverRole approverRole,
        Instant approvedAt,
        String approvalStatus,
        UUID paymentId,
        int version,
        Instant createdAt,
        Instant updatedAt
) {}
