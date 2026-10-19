package com.eia.assumedclaims.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Result of a bordereau ingest, including per-line duplicate and rejection decisions. */
public record BordereauIngestResult(
        BordereauBatch batch,
        List<LineResult> lines,
        boolean replayed
) {
    public record LineResult(
            int lineNumber,
            String adviceNumber,
            BordereauLineStatus status,
            String reason,
            UUID adviceId,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal reportedAmount
    ) {}
}
