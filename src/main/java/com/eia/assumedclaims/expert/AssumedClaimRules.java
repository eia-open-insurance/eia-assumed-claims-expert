package com.eia.assumedclaims.expert;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Cross-field invariants that Bean Validation cannot express. The same rules are enforced by the
 * domain service; the expert keeps them as a last line of defence.
 */
final class AssumedClaimRules {
    private AssumedClaimRules() {}

    static void validateCreate(CreateAssumedClaimRequest request) {
        List<FieldIssue> issues = new ArrayList<>();
        if (request.lossDate() != null && request.reportDate() != null
                && request.reportDate().isBefore(request.lossDate())) {
            issues.add(new FieldIssue(
                    "reportDate",
                    "REPORT_BEFORE_LOSS",
                    "reportDate must be on or after lossDate"
            ));
        }
        if (request.reserveAmount() != null && request.reserveAmount().compareTo(BigDecimal.ZERO) < 0) {
            issues.add(new FieldIssue(
                    "reserveAmount",
                    "RESERVE_NEGATIVE",
                    "reserveAmount must be greater than or equal to zero"
            ));
        }
        if (!issues.isEmpty()) {
            throw ApiException.validation("The assumed claim violates a business invariant.", issues);
        }
    }
}
