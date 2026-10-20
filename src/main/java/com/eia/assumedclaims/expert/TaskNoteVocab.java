package com.eia.assumedclaims.expert;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Closed vocabularies and primitive validation shared by claim tasks and
 * append-only diary notes. Enumerations are stored as {@code varchar} per
 * SPEC §10.4; the sets below are the allowlists the API enforces.
 */
final class TaskNoteVocab {
    static final Set<String> TASK_PRIORITIES = Set.of("LOW", "NORMAL", "HIGH", "URGENT");
    static final Set<String> TASK_STATUSES = Set.of("OPEN", "COMPLETE", "CANCELLED");
    static final Set<String> NOTE_TYPES = Set.of(
            "GENERAL", "DIARY", "COVERAGE", "RESERVE", "LITIGATION", "SETTLEMENT");

    private TaskNoteVocab() {
    }

    static String normalize(Set<String> allowed, String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw invalid(field, "REQUIRED", field + " is required");
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(value)) {
            throw invalid(field, "UNKNOWN_VALUE",
                    "Unknown " + field + ": " + raw + "; must be one of "
                            + String.join(", ", allowed.stream().sorted().toList()));
        }
        return value;
    }

    static String requiredText(String raw, String field, int maxLength) {
        if (raw == null || raw.isBlank()) {
            throw invalid(field, "REQUIRED", field + " is required");
        }
        String value = raw.trim();
        if (value.length() > maxLength) {
            throw invalid(field, "TOO_LONG",
                    field + " must be at most " + maxLength + " characters");
        }
        return value;
    }

    static String optionalText(String raw, String field, int maxLength) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.length() > maxLength) {
            throw invalid(field, "TOO_LONG",
                    field + " must be at most " + maxLength + " characters");
        }
        return value;
    }

    static LocalDate requiredDate(LocalDate value, String field) {
        if (value == null) {
            throw invalid(field, "REQUIRED", field + " is required");
        }
        return value;
    }

    private static ApiException invalid(String field, String code, String detail) {
        return ApiException.validation(detail, List.of(new FieldIssue(field, code, detail)));
    }
}
