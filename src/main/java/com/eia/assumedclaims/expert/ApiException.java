package com.eia.assumedclaims.expert;

import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

/**
 * Domain error that the API layer renders as a structured {@link ApiError}.
 */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final String category;
    private final String title;
    private final boolean retryable;
    private final transient List<FieldIssue> errors;

    private ApiException(
            HttpStatus status,
            String code,
            String category,
            String title,
            String detail,
            boolean retryable,
            List<FieldIssue> errors
    ) {
        super(detail);
        this.status = status;
        this.code = code;
        this.category = category;
        this.title = title;
        this.retryable = retryable;
        this.errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String category() {
        return category;
    }

    public String title() {
        return title;
    }

    public boolean retryable() {
        return retryable;
    }

    public List<FieldIssue> errors() {
        return errors;
    }

    public static ApiException assumedClaimNotFound(UUID id) {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "EIA-RNS-7001",
                "NOT_FOUND",
                "Assumed claim not found",
                "Assumed claim " + id + " was not found.",
                false,
                List.of()
        );
    }

    public static ApiException validation(String detail, List<FieldIssue> errors) {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                "EIA-RNS-7004",
                "VALIDATION",
                "Request validation failed",
                detail,
                false,
                errors
        );
    }

    public static ApiException invalidTransition(AssumedClaimStatus from, AssumedClaimStatus to) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "EIA-RNS-7003",
                "CONFLICT",
                "Invalid status transition",
                "Assumed claim status cannot move from " + from + " to " + to + ".",
                false,
                List.of(new FieldIssue("targetStatus", "INVALID_STATUS_TRANSITION",
                        from + " cannot transition to " + to))
        );
    }

    public static ApiException versionConflict(int expectedVersion) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "EIA-RNS-7006",
                "CONFLICT",
                "Version conflict",
                "Assumed claim was modified by another request; expected version " + expectedVersion + ".",
                true,
                List.of()
        );
    }
}
