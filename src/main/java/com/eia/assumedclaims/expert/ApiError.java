package com.eia.assumedclaims.expert;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Locale;

/**
 * Structured error body modelled on the canonical {@code Problem} shape (SPEC §12.2).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        String code,
        String category,
        boolean retryable,
        List<FieldIssue> errors
) {
    public static ApiError from(ApiException exception, String instance) {
        return new ApiError(
                typeFor(exception.code()),
                exception.title(),
                exception.status().value(),
                exception.getMessage(),
                instance,
                exception.code(),
                exception.category(),
                exception.retryable(),
                exception.errors()
        );
    }

    public static ApiError bookScope(String instance) {
        return new ApiError(
                typeFor("EIA-RNS-7005"),
                "Not found",
                404,
                "No resource is available in the requested book scope.",
                instance,
                "EIA-RNS-7005",
                "NOT_FOUND",
                false,
                List.of()
        );
    }

    private static String typeFor(String code) {
        return "https://errors.eia.example/" + code.toLowerCase(Locale.ROOT);
    }
}
