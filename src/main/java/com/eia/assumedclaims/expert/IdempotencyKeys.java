package com.eia.assumedclaims.expert;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Validation and canonical hashing for the optional {@code Idempotency-Key}
 * on claim task and note creation.
 *
 * <p>The key is tenant-scoped and shared by every claim command type; the
 * command type is part of the idempotency table primary key, so the same key can
 * be used once for a task and once for a note. The request hash is computed from
 * a length-prefixed, field-ordered canonical form of the <em>normalized</em>
 * command, so a retry with the same business request replays while a reuse with
 * a different request is rejected. Length prefixes keep free-text bodies from
 * colliding with field separators.</p>
 */
final class IdempotencyKeys {
    static final int MIN_LENGTH = 1;
    static final int MAX_LENGTH = 64;

    private IdempotencyKeys() {
    }

    /**
     * @return the trimmed key, or {@code null} when the header is absent
     * @throws ApiException 400 when a supplied key is empty or longer than 64 characters
     */
    static String normalize(String rawKey) {
        if (rawKey == null) {
            return null;
        }
        String key = rawKey.trim();
        if (key.length() < MIN_LENGTH || key.length() > MAX_LENGTH) {
            String message =
                    "Idempotency-Key must be between " + MIN_LENGTH + " and " + MAX_LENGTH
                            + " characters.";
            throw ApiException.validation(message, List.of(
                    new FieldIssue("Idempotency-Key", "INVALID_IDEMPOTENCY_KEY", message)));
        }
        return key;
    }

    static String taskRequestHash(UUID claimId,
                                  String title,
                                  String description,
                                  LocalDate dueDate,
                                  String priority,
                                  String assignedTo) {
        return sha256(canonical(
                claimId.toString(),
                text(title),
                text(description),
                dueDate == null ? "" : dueDate.toString(),
                upper(priority),
                text(assignedTo)));
    }

    static String noteRequestHash(UUID claimId, String noteType, String body, String author) {
        return sha256(canonical(
                claimId.toString(),
                upper(noteType),
                text(body),
                text(author)));
    }

    private static String canonical(String... fields) {
        StringBuilder canonical = new StringBuilder();
        for (String field : fields) {
            String value = field == null ? "" : field;
            canonical.append(value.length()).append(':').append(value).append('\n');
        }
        return canonical.toString();
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", ex);
        }
    }
}
