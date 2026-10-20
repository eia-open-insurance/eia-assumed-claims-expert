package com.eia.assumedclaims.expert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Idempotent command wrapper for claim task and note creation.
 *
 * <p>The reservation, the created row, and its outbox event are written in one
 * transaction, so a failed command leaves no key behind and a replay writes no
 * duplicate row or event. The same key with an identical canonical request
 * returns the stored 201 body with {@code replayed=true}; reusing the key for a
 * different request is 409 {@code EIA-RNS-4091} {@code IDEMPOTENCY_KEY_REUSED}.</p>
 */
@Component
public class ClaimCommandIdempotency {
    public static final String TASK_CREATE = "CLAIM_TASK_CREATE";
    public static final String NOTE_CREATE = "CLAIM_NOTE_CREATE";

    private final ClaimCommandIdempotencyRepository repository;
    private final ObjectMapper objectMapper;

    public ClaimCommandIdempotency(ClaimCommandIdempotencyRepository repository,
                                   ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public <T> CommandResult<T> run(UUID tenantId,
                                    String commandType,
                                    String key,
                                    String requestHash,
                                    UUID claimId,
                                    Instant now,
                                    Class<T> responseType,
                                    Supplier<T> action) {
        Optional<ClaimCommandIdempotencyRecord> existing =
                repository.find(tenantId, commandType, key);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash, responseType);
        }
        if (!repository.reserve(tenantId, commandType, key, requestHash, claimId, now)) {
            ClaimCommandIdempotencyRecord raced = repository.find(tenantId, commandType, key)
                    .orElseThrow(ClaimCommandIdempotency::inconsistent);
            return replay(raced, requestHash, responseType);
        }
        T body = action.get();
        if (!repository.complete(tenantId, commandType, key, encode(body))) {
            throw inconsistent();
        }
        return new CommandResult<>(body, false);
    }

    private <T> CommandResult<T> replay(ClaimCommandIdempotencyRecord record,
                                        String requestHash,
                                        Class<T> responseType) {
        if (!record.requestHash().equals(requestHash)) {
            throw ApiException.idempotencyKeyReused();
        }
        String body = record.responseBody();
        if (body == null || body.isBlank()) {
            throw inconsistent();
        }
        try {
            T value = objectMapper.readValue(body, responseType);
            if (value == null) {
                throw inconsistent();
            }
            return new CommandResult<>(value, true);
        } catch (JsonProcessingException ex) {
            throw inconsistent();
        }
    }

    private String encode(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw inconsistent();
        }
    }

    private static ApiException inconsistent() {
        return ApiException.idempotencyRecordInconsistent();
    }
}
