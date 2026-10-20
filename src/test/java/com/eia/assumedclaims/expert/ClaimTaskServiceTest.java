package com.eia.assumedclaims.expert;

import com.eia.platform.libs.events.EventJson;
import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.eia.assumedclaims.expert.TaskNoteFixtures.CLAIM_ID;
import static com.eia.assumedclaims.expert.TaskNoteFixtures.NOW;
import static com.eia.assumedclaims.expert.TaskNoteFixtures.TASK_DUE_DATE;
import static com.eia.assumedclaims.expert.TaskNoteFixtures.TASK_ID;
import static com.eia.assumedclaims.expert.TaskNoteFixtures.TENANT;
import static com.eia.assumedclaims.expert.TaskNoteFixtures.claim;
import static com.eia.assumedclaims.expert.TaskNoteFixtures.task;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaimTaskServiceTest {
    @Mock
    private ClaimTaskRepository repository;
    @Mock
    private AssumedClaimService claimService;
    @Mock
    private AssumedClaimRepository claims;
    @Mock
    private OutboxRepository outbox;

    private ClaimTaskService service;

    @BeforeEach
    void setUp() {
        service = new ClaimTaskService(repository, claimService, claims, outbox,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void listsTasksForTheClaim() {
        claimExists(0);
        List<ClaimTask> tasks = List.of(task("OPEN", 1));
        when(repository.findAll(TENANT, CLAIM_ID)).thenReturn(tasks);

        List<ClaimTask> found = service.list(TENANT, CLAIM_ID);

        assertSame(tasks, found);
        verifyNoInteractions(outbox);
    }

    @Test
    void createsATaskAndWritesTheTaskCreatedEvent() {
        claimExists(0);
        when(repository.nextTaskSequence()).thenReturn(1L);
        when(repository.insert(any(UUID.class), eq(TENANT), eq(CLAIM_ID), eq("TSK-2026-0000001"),
                eq("Review medical records"), eq("Order records and index the file"),
                eq(TASK_DUE_DATE), eq("HIGH"), eq("adjuster@example.com"), any(Instant.class)))
                .thenReturn(task("OPEN", 1));

        ClaimTask created = service.create(TENANT, CLAIM_ID, createRequest());

        assertEquals("OPEN", created.status());
        assertEquals(1, created.version());
        assertEquals("TSK-2026-0000001", created.taskNumber());
        verify(claims).touch(TENANT, CLAIM_ID);

        OutboxEvent event = capturedEvent();
        assertEquals(AssumedClaimEvents.TASK_CREATED, event.eventType());
        assertEquals("AssumedClaim", event.aggregateType());
        assertEquals(CLAIM_ID, event.aggregateId());
        assertEquals(2L, event.aggregateVersion());
        assertEquals(TENANT, event.tenantId());
        assertTrue(event.headers().contains("\"book\":\"ASSUMED\""));

        Map<String, Object> payload = payload(event);
        assertEquals(CLAIM_ID.toString(), payload.get("assumedClaimId"));
        assertEquals("RNS-2026-0000001", payload.get("assumedClaimNumber"));
        assertEquals(TASK_ID.toString(), payload.get("taskId"));
        assertEquals("TSK-2026-0000001", payload.get("taskNumber"));
        assertEquals("Review medical records", payload.get("title"));
        assertEquals("Order records and index the file", payload.get("description"));
        assertEquals("OPEN", payload.get("status"));
        assertEquals("NOTIFIED", payload.get("claimStatus"));
        assertEquals("HIGH", payload.get("priority"));
        assertEquals("2026-04-15", payload.get("dueDate"));
        assertEquals("adjuster@example.com", payload.get("assignedTo"));
        assertEquals(1, payload.get("version"));
        assertEquals(1, payload.get("taskVersion"));
    }

    @Test
    void completesATaskWithATaskCompletedEvent() {
        when(claimService.requireClaim(TENANT, CLAIM_ID))
                .thenReturn(claim(AssumedClaimStatus.NOTIFIED, 1));
        when(repository.findById(TENANT, CLAIM_ID, TASK_ID))
                .thenReturn(Optional.of(task("OPEN", 1)));
        when(repository.updateStatus(eq(TENANT), eq(CLAIM_ID), eq(TASK_ID), eq("COMPLETE"),
                eq(1L), any(Instant.class))).thenReturn(Optional.of(task("COMPLETE", 2)));

        ClaimTask completed = service.changeStatus(TENANT, CLAIM_ID, TASK_ID,
                new TaskStatusChangeRequest("COMPLETE", 1L));

        assertEquals("COMPLETE", completed.status());
        assertEquals(2, completed.version());
        assertNotNull(completed.completedAt());
        verify(claims).touch(TENANT, CLAIM_ID);

        OutboxEvent event = capturedEvent();
        assertEquals(AssumedClaimEvents.TASK_COMPLETED, event.eventType());
        assertEquals(3L, event.aggregateVersion());
        Map<String, Object> payload = payload(event);
        assertEquals("COMPLETE", payload.get("status"));
        assertEquals("OPEN", payload.get("from"));
        assertEquals("COMPLETE", payload.get("to"));
        assertEquals(2, payload.get("taskVersion"));
        assertNotNull(payload.get("completedAt"));
    }

    @Test
    void cancellingATaskWritesATaskUpdatedEvent() {
        claimExists(1);
        when(repository.findById(TENANT, CLAIM_ID, TASK_ID))
                .thenReturn(Optional.of(task("OPEN", 1)));
        when(repository.updateStatus(eq(TENANT), eq(CLAIM_ID), eq(TASK_ID), eq("CANCELLED"),
                eq(1L), any(Instant.class))).thenReturn(Optional.of(task("CANCELLED", 2)));

        ClaimTask cancelled = service.changeStatus(TENANT, CLAIM_ID, TASK_ID,
                new TaskStatusChangeRequest("CANCELLED", 1L));

        assertEquals("CANCELLED", cancelled.status());
        assertNull(cancelled.completedAt());

        OutboxEvent event = capturedEvent();
        assertEquals(AssumedClaimEvents.TASK_UPDATED, event.eventType());
        Map<String, Object> payload = payload(event);
        assertEquals("CANCELLED", payload.get("status"));
        assertEquals("CANCELLED", payload.get("to"));
        assertEquals(3L, event.aggregateVersion());
    }

    @Test
    void reopensACompletedTaskWithATaskUpdatedEvent() {
        claimExists(1);
        when(repository.findById(TENANT, CLAIM_ID, TASK_ID))
                .thenReturn(Optional.of(task("COMPLETE", 2)));
        when(repository.updateStatus(eq(TENANT), eq(CLAIM_ID), eq(TASK_ID), eq("OPEN"),
                eq(2L), any(Instant.class))).thenReturn(Optional.of(task("OPEN", 3)));

        ClaimTask reopened = service.changeStatus(TENANT, CLAIM_ID, TASK_ID,
                new TaskStatusChangeRequest("OPEN", 2L));

        assertEquals("OPEN", reopened.status());
        assertNull(reopened.completedAt());

        OutboxEvent event = capturedEvent();
        assertEquals(AssumedClaimEvents.TASK_UPDATED, event.eventType());
        Map<String, Object> payload = payload(event);
        assertEquals("COMPLETE", payload.get("from"));
        assertEquals("OPEN", payload.get("to"));
        assertEquals(3, payload.get("taskVersion"));
    }

    @Test
    void staleTaskVersionIsAConflictAndWritesNoEvent() {
        claimExists(1);
        when(repository.findById(TENANT, CLAIM_ID, TASK_ID))
                .thenReturn(Optional.of(task("OPEN", 1)));

        ApiException error = assertThrows(ApiException.class,
                () -> service.changeStatus(TENANT, CLAIM_ID, TASK_ID,
                        new TaskStatusChangeRequest("COMPLETE", 2L)));

        assertEquals(HttpStatus.CONFLICT, error.status());
        assertEquals("EIA-RNS-7802", error.code());
        assertTrue(error.getMessage().contains("expected version 2"));
        verify(repository, never()).updateStatus(any(), any(), any(), any(), anyLong(), any());
        verify(claims, never()).touch(any(), any());
        verifyNoInteractions(outbox);
    }

    @Test
    void repeatingTheCurrentStatusIsAnIdempotentNoOp() {
        claimExists(1);
        ClaimTask current = task("OPEN", 1);
        when(repository.findById(TENANT, CLAIM_ID, TASK_ID)).thenReturn(Optional.of(current));

        ClaimTask result = service.changeStatus(TENANT, CLAIM_ID, TASK_ID,
                new TaskStatusChangeRequest("OPEN", 1L));

        assertSame(current, result);
        verify(repository, never()).updateStatus(any(), any(), any(), any(), anyLong(), any());
        verify(claims, never()).touch(any(), any());
        verifyNoInteractions(outbox);
    }

    @Test
    void rejectsAnIllegalTransition() {
        claimExists(1);
        when(repository.findById(TENANT, CLAIM_ID, TASK_ID))
                .thenReturn(Optional.of(task("COMPLETE", 2)));

        ApiException error = assertThrows(ApiException.class,
                () -> service.changeStatus(TENANT, CLAIM_ID, TASK_ID,
                        new TaskStatusChangeRequest("CANCELLED", 2L)));

        assertEquals(HttpStatus.BAD_REQUEST, error.status());
        assertTrue(error.getMessage().contains("Illegal claim task status transition"));
        verify(repository, never()).updateStatus(any(), any(), any(), any(), anyLong(), any());
        verifyNoInteractions(outbox);
    }

    @Test
    void rejectsAMissingTaskVersion() {
        claimExists(0);
        when(repository.findById(TENANT, CLAIM_ID, TASK_ID))
                .thenReturn(Optional.of(task("OPEN", 1)));

        ApiException error = assertThrows(ApiException.class,
                () -> service.changeStatus(TENANT, CLAIM_ID, TASK_ID,
                        new TaskStatusChangeRequest("COMPLETE", null)));

        assertEquals(HttpStatus.BAD_REQUEST, error.status());
        assertTrue(error.getMessage().contains("version is required"));
        verifyNoInteractions(outbox);
    }

    @Test
    void aTaskOutsideTheTenantAndClaimIsNotFound() {
        claimExists(0);
        when(repository.findById(TENANT, CLAIM_ID, TASK_ID)).thenReturn(Optional.empty());

        ApiException error = assertThrows(ApiException.class, () -> service.changeStatus(TENANT,
                CLAIM_ID, TASK_ID, new TaskStatusChangeRequest("COMPLETE", 1L)));

        assertEquals(HttpStatus.NOT_FOUND, error.status());
        assertEquals("EIA-RNS-7801", error.code());
        verifyNoInteractions(outbox);
    }

    @Test
    void requiresATitle() {
        claimExists(0);

        ApiException error = assertThrows(ApiException.class,
                () -> service.create(TENANT, CLAIM_ID,
                        new CreateTaskRequest("  ", null, TASK_DUE_DATE, "HIGH", null)));

        assertTrue(error.getMessage().contains("title is required"));
        verify(repository, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any());
        verifyNoInteractions(outbox);
    }

    @Test
    void requiresAKnownPriority() {
        claimExists(0);

        ApiException error = assertThrows(ApiException.class,
                () -> service.create(TENANT, CLAIM_ID,
                        new CreateTaskRequest("Review medical records", null, TASK_DUE_DATE,
                                "CRITICAL", null)));

        assertTrue(error.getMessage().contains("Unknown priority"));
        verifyNoInteractions(outbox);
    }

    @Test
    void requiresADueDate() {
        claimExists(0);

        ApiException error = assertThrows(ApiException.class,
                () -> service.create(TENANT, CLAIM_ID,
                        new CreateTaskRequest("Review medical records", null, null, "HIGH", null)));

        assertTrue(error.getMessage().contains("dueDate is required"));
        verifyNoInteractions(outbox);
    }

    private void claimExists(int version) {
        when(claimService.requireClaim(TENANT, CLAIM_ID))
                .thenReturn(claim(AssumedClaimStatus.NOTIFIED, version));
    }

    private static CreateTaskRequest createRequest() {
        return new CreateTaskRequest("Review medical records", "Order records and index the file",
                TASK_DUE_DATE, "HIGH", "adjuster@example.com");
    }

    private OutboxEvent capturedEvent() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(captor.capture());
        return captor.getValue();
    }

    private static Map<String, Object> payload(OutboxEvent event) {
        return EventJson.toMap(event.payload());
    }
}
