package com.eia.assumedclaims.expert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.eia.assumedclaims.expert.TaskNoteFixtures.CLAIM_ID;
import static com.eia.assumedclaims.expert.TaskNoteFixtures.NOW;
import static com.eia.assumedclaims.expert.TaskNoteFixtures.TENANT;
import static com.eia.assumedclaims.expert.TaskNoteFixtures.task;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaimCommandIdempotencyTest {
    @Mock
    private ClaimCommandIdempotencyRepository repository;

    private ObjectMapper objectMapper;
    private ClaimCommandIdempotency idempotency;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        idempotency = new ClaimCommandIdempotency(repository, objectMapper);
    }

    @Test
    void aFirstCallReservesRunsTheActionAndStoresTheResponseBody() {
        when(repository.find(TENANT, ClaimCommandIdempotency.TASK_CREATE, "key-1"))
                .thenReturn(Optional.empty());
        when(repository.reserve(eq(TENANT), eq(ClaimCommandIdempotency.TASK_CREATE), eq("key-1"),
                eq("hash-1"), eq(CLAIM_ID), any(Instant.class))).thenReturn(true);
        when(repository.complete(eq(TENANT), eq(ClaimCommandIdempotency.TASK_CREATE), eq("key-1"),
                anyString())).thenReturn(true);
        ClaimTask stored = task("OPEN", 1);

        CommandResult<ClaimTask> result = idempotency.run(TENANT,
                ClaimCommandIdempotency.TASK_CREATE, "key-1", "hash-1", CLAIM_ID, NOW,
                ClaimTask.class, () -> stored);

        assertFalse(result.replayed());
        assertEquals(stored, result.body());

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(repository).complete(eq(TENANT), eq(ClaimCommandIdempotency.TASK_CREATE),
                eq("key-1"), body.capture());
        assertTrue(body.getValue().contains("\"taskNumber\":\"TSK-2026-0000001\""));
    }

    @Test
    void anIdenticalReplayReturnsTheStoredBodyWithoutRunningTheAction() throws Exception {
        ClaimTask stored = task("OPEN", 1);
        String responseBody = objectMapper.writeValueAsString(stored);
        when(repository.find(TENANT, ClaimCommandIdempotency.TASK_CREATE, "key-1"))
                .thenReturn(Optional.of(new ClaimCommandIdempotencyRecord(
                        ClaimCommandIdempotency.TASK_CREATE, "hash-1", CLAIM_ID, responseBody)));
        AtomicBoolean actionRan = new AtomicBoolean(false);

        CommandResult<ClaimTask> result = idempotency.run(TENANT,
                ClaimCommandIdempotency.TASK_CREATE, "key-1", "hash-1", CLAIM_ID, NOW,
                ClaimTask.class, () -> {
                    actionRan.set(true);
                    return stored;
                });

        assertTrue(result.replayed());
        assertFalse(actionRan.get());
        assertEquals(stored.id(), result.body().id());
        assertEquals(stored.taskNumber(), result.body().taskNumber());
        assertEquals(stored.createdAt(), result.body().createdAt());
        verify(repository, never()).reserve(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aDifferentBodyUnderTheSameKeyIs409IdempotencyKeyReused() throws Exception {
        String responseBody = objectMapper.writeValueAsString(task("OPEN", 1));
        when(repository.find(eq(TENANT), eq(ClaimCommandIdempotency.TASK_CREATE), eq("key-1")))
                .thenReturn(Optional.of(new ClaimCommandIdempotencyRecord(
                        ClaimCommandIdempotency.TASK_CREATE, "hash-1", CLAIM_ID, responseBody)));

        ApiException error = assertThrows(ApiException.class, () -> idempotency.run(TENANT,
                ClaimCommandIdempotency.TASK_CREATE, "key-1", "hash-2", CLAIM_ID, NOW,
                ClaimTask.class, () -> task("OPEN", 1)));

        assertEquals(409, error.status().value());
        assertEquals("EIA-RNS-4091", error.code());
        assertEquals("CONFLICT", error.category());
        assertEquals("IDEMPOTENCY_KEY_REUSED", error.errors().getFirst().code());
        assertEquals("Idempotency-Key", error.errors().getFirst().field());
    }

    @Test
    void losingTheReservationRaceReplaysTheWinnersRow() throws Exception {
        ClaimTask stored = task("OPEN", 1);
        String responseBody = objectMapper.writeValueAsString(stored);
        when(repository.find(TENANT, ClaimCommandIdempotency.TASK_CREATE, "key-1"))
                .thenReturn(Optional.empty(), Optional.of(new ClaimCommandIdempotencyRecord(
                        ClaimCommandIdempotency.TASK_CREATE, "hash-1", CLAIM_ID, responseBody)));
        when(repository.reserve(eq(TENANT), eq(ClaimCommandIdempotency.TASK_CREATE), eq("key-1"),
                eq("hash-1"), eq(CLAIM_ID), any(Instant.class))).thenReturn(false);
        AtomicBoolean actionRan = new AtomicBoolean(false);

        CommandResult<ClaimTask> result = idempotency.run(TENANT,
                ClaimCommandIdempotency.TASK_CREATE, "key-1", "hash-1", CLAIM_ID, NOW,
                ClaimTask.class, () -> {
                    actionRan.set(true);
                    return stored;
                });

        assertTrue(result.replayed());
        assertFalse(actionRan.get());
        assertEquals(stored.id(), result.body().id());
        assertEquals(stored.taskNumber(), result.body().taskNumber());
    }

    @Test
    void anUnreadableStoredBodyIsAnInternalInvariantFailure() {
        when(repository.find(TENANT, ClaimCommandIdempotency.NOTE_CREATE, "key-1"))
                .thenReturn(Optional.of(new ClaimCommandIdempotencyRecord(
                        ClaimCommandIdempotency.NOTE_CREATE, "hash-1", CLAIM_ID, null)));

        ApiException error = assertThrows(ApiException.class, () -> idempotency.run(TENANT,
                ClaimCommandIdempotency.NOTE_CREATE, "key-1", "hash-1", CLAIM_ID, NOW,
                ClaimNote.class, () -> null));

        assertEquals(500, error.status().value());
        assertEquals("EIA-RNS-7099", error.code());
    }
}
