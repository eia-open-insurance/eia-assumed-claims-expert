package com.eia.assumedclaims.expert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end task and note persistence, ordering, tenant isolation, outbox
 * payloads, and transactional rollback against the H2 test schema.
 */
@SpringBootTest
@ActiveProfiles("test")
class ClaimTaskNoteOutboxIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID CONTRACT = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID OTHER_TENANT = UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final LocalDate LOSS_DATE = LocalDate.parse("2026-09-01");
    private static final LocalDate REPORT_DATE = LocalDate.parse("2026-09-02");

    @Autowired
    private AssumedClaimService claimService;
    @Autowired
    private ClaimTaskService taskService;
    @Autowired
    private ClaimNoteService noteService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void clean() {
        transaction = new TransactionTemplate(transactionManager);
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM claim_note");
        jdbc.update("DELETE FROM claim_task");
        jdbc.update("DELETE FROM assumed_claim_audit");
        jdbc.update("DELETE FROM assumed_commutation");
        jdbc.update("DELETE FROM assumed_recovery");
        jdbc.update("DELETE FROM assumed_cash_call");
        jdbc.update("DELETE FROM assumed_reserve_history");
        jdbc.update("DELETE FROM assumed_loss_advice");
        jdbc.update("DELETE FROM assumed_dispute");
        jdbc.update("DELETE FROM assumed_bordereau");
        jdbc.update("DELETE FROM assumed_claim");
    }

    // -------------------------------------------------------------------- tasks
    @Test
    void taskLifecycleWritesCreatedAndCompletedEvents() {
        AssumedClaim claim = createClaim();
        ClaimTask task = taskService.create(TENANT, claim.id(), new CreateTaskRequest(
                "Review medical records", "Order records and index the file",
                LocalDate.of(2026, 4, 15), "HIGH", "adjuster@example.com"));

        assertThat(task.status()).isEqualTo("OPEN");
        assertThat(task.version()).isEqualTo(1);
        assertThat(task.taskNumber()).startsWith("TSK-");
        assertThat(task.completedAt()).isNull();
        assertThat(claimVersion(claim.id())).isEqualTo(1);

        Map<String, Object> createdEvent = lastEvent(claim.id());
        assertThat(createdEvent.get("event_type")).isEqualTo("assumed.claim.task-created");
        assertThat(createdEvent.get("aggregate_type")).isEqualTo("AssumedClaim");
        assertThat(aggregateVersion(createdEvent)).isEqualTo(2L);
        assertThat(createdEvent.get("tenant_id")).isEqualTo(TENANT);
        assertThat(createdEvent.get("partition_key")).isEqualTo(claim.id().toString());
        String createdPayload = (String) createdEvent.get("payload");
        assertThat(createdPayload)
                .contains("\"assumedClaimId\":\"" + claim.id() + "\"")
                .contains("\"assumedClaimNumber\":\"" + claim.assumedClaimNumber() + "\"")
                .contains("\"taskId\":\"" + task.id() + "\"")
                .contains("\"taskNumber\":\"" + task.taskNumber() + "\"")
                .contains("\"title\":\"Review medical records\"")
                .contains("\"description\":\"Order records and index the file\"")
                .contains("\"status\":\"OPEN\"")
                .contains("\"claimStatus\":\"NOTIFIED\"")
                .contains("\"priority\":\"HIGH\"")
                .contains("\"dueDate\":\"2026-04-15\"")
                .contains("\"assignedTo\":\"adjuster@example.com\"")
                .contains("\"version\":1")
                .contains("\"taskVersion\":1");
        String headers = (String) createdEvent.get("headers");
        assertThat(headers)
                .contains("\"book\":\"ASSUMED\"")
                .contains("\"vertical\":\"assumed\"");

        ClaimTask completed = taskService.changeStatus(TENANT, claim.id(), task.id(),
                new TaskStatusChangeRequest("COMPLETE", task.version()));

        assertThat(completed.status()).isEqualTo("COMPLETE");
        assertThat(completed.version()).isEqualTo(2);
        assertThat(completed.completedAt()).isNotNull();
        assertThat(claimVersion(claim.id())).isEqualTo(2);

        Map<String, Object> completedEvent = lastEvent(claim.id());
        assertThat(completedEvent.get("event_type")).isEqualTo("assumed.claim.task-completed");
        assertThat(aggregateVersion(completedEvent)).isEqualTo(3L);
        String completedPayload = (String) completedEvent.get("payload");
        assertThat(completedPayload)
                .contains("\"assumedClaimId\":\"" + claim.id() + "\"")
                .contains("\"taskId\":\"" + task.id() + "\"")
                .contains("\"status\":\"COMPLETE\"")
                .contains("\"claimStatus\":\"NOTIFIED\"")
                .contains("\"from\":\"OPEN\"")
                .contains("\"to\":\"COMPLETE\"")
                .contains("\"taskVersion\":2");
        assertThat(completedPayload).contains("\"completedAt\":\"");
    }

    @Test
    void cancellingATaskWritesTheTaskUpdatedEvent() {
        AssumedClaim claim = createClaim();
        ClaimTask task = createTask(claim, "Review medical records",
                LocalDate.of(2026, 4, 15), "HIGH");

        ClaimTask cancelled = taskService.changeStatus(TENANT, claim.id(), task.id(),
                new TaskStatusChangeRequest("CANCELLED", task.version()));

        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.completedAt()).isNull();

        Map<String, Object> event = lastEvent(claim.id());
        assertThat(event.get("event_type")).isEqualTo("assumed.claim.task-updated");
        assertThat(aggregateVersion(event)).isEqualTo(3L);
        String payload = (String) event.get("payload");
        assertThat(payload)
                .contains("\"status\":\"CANCELLED\"")
                .contains("\"from\":\"OPEN\"")
                .contains("\"to\":\"CANCELLED\"");
        assertThat(payload).doesNotContain("\"completedAt\":");
    }

    @Test
    void taskListPutsOpenTasksFirstByDueDateThenCompleted() {
        AssumedClaim claim = createClaim();
        ClaimTask late = createTask(claim, "Late task", LocalDate.of(2026, 5, 1), "NORMAL");
        ClaimTask early = createTask(claim, "Early task", LocalDate.of(2026, 4, 1), "HIGH");
        ClaimTask done = createTask(claim, "Done task", LocalDate.of(2026, 3, 1), "LOW");

        taskService.changeStatus(TENANT, claim.id(), done.id(),
                new TaskStatusChangeRequest("COMPLETE", done.version()));

        List<ClaimTask> tasks = taskService.list(TENANT, claim.id());

        assertThat(tasks).extracting(ClaimTask::id)
                .containsExactly(early.id(), late.id(), done.id());
        assertThat(tasks).extracting(ClaimTask::status)
                .containsExactly("OPEN", "OPEN", "COMPLETE");
        assertThat(tasks).extracting(ClaimTask::dueDate)
                .containsExactly(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 5, 1),
                        LocalDate.of(2026, 3, 1));
    }

    @Test
    void staleTaskVersionIs409AndWritesNoEvent() {
        AssumedClaim claim = createClaim();
        ClaimTask task = createTask(claim, "Review medical records",
                LocalDate.of(2026, 4, 15), "HIGH");
        taskService.changeStatus(TENANT, claim.id(), task.id(),
                new TaskStatusChangeRequest("COMPLETE", task.version()));

        assertThatThrownBy(() -> taskService.changeStatus(TENANT, claim.id(), task.id(),
                new TaskStatusChangeRequest("CANCELLED", task.version())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expected version 1")
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.status().value()).isEqualTo(409));

        assertThat(taskStatus(task.id())).isEqualTo("COMPLETE");
        assertThat(taskVersion(task.id())).isEqualTo(2L);
        assertThat(countEvents(claim.id(), "assumed.claim.task-completed")).isEqualTo(1);
        assertThat(countEvents(claim.id(), "assumed.claim.task-updated")).isZero();
    }

    @Test
    void tasksAndNotesAreTenantAndClaimIsolated() {
        AssumedClaim claim = createClaim();
        AssumedClaim otherClaim = createClaim();
        ClaimTask task = createTask(claim, "Review medical records",
                LocalDate.of(2026, 4, 15), "HIGH");
        noteService.add(TENANT, claim.id(),
                new CreateNoteRequest("DIARY", "Called the claimant", "adjuster@example.com"));

        assertThatThrownBy(() -> taskService.list(OTHER_TENANT, claim.id()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("was not found");
        assertThatThrownBy(() -> taskService.create(OTHER_TENANT, claim.id(), new CreateTaskRequest(
                "Intruder task", null, LocalDate.of(2026, 4, 15), "LOW", null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("was not found");
        assertThatThrownBy(() -> taskService.changeStatus(OTHER_TENANT, claim.id(), task.id(),
                new TaskStatusChangeRequest("COMPLETE", task.version())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("was not found");
        assertThatThrownBy(() -> noteService.list(OTHER_TENANT, claim.id()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("was not found");
        assertThatThrownBy(() -> taskService.changeStatus(TENANT, otherClaim.id(), task.id(),
                new TaskStatusChangeRequest("COMPLETE", task.version())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Claim task");
        assertThat(taskService.list(TENANT, otherClaim.id())).isEmpty();
        assertThat(noteService.list(TENANT, otherClaim.id())).isEmpty();
        assertThat(count("claim_task")).isEqualTo(1);
        assertThat(count("claim_note")).isEqualTo(1);
    }

    @Test
    void taskCreateRollbackLeavesNoTaskVersionChangeOrEvent() {
        AssumedClaim claim = createClaim();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            taskService.create(TENANT, claim.id(), new CreateTaskRequest(
                    "Review medical records", null, LocalDate.of(2026, 4, 15), "HIGH", null));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(count("claim_task")).isZero();
        assertThat(claimVersion(claim.id())).isZero();
        assertThat(count("outbox_event")).isEqualTo(1);
        assertThat(countEvents(claim.id(), "assumed.claim.task-created")).isZero();
    }

    // -------------------------------------------------------------------- notes
    @Test
    void notesAppendAndListNewestFirst() {
        AssumedClaim claim = createClaim();
        ClaimNote first = noteService.add(TENANT, claim.id(),
                new CreateNoteRequest("DIARY", "Called the claimant", "adjuster@example.com"));
        ClaimNote second = noteService.add(TENANT, claim.id(),
                new CreateNoteRequest("SETTLEMENT", "Authority requested", "manager@example.com"));

        List<ClaimNote> notes = noteService.list(TENANT, claim.id());

        assertThat(notes).extracting(ClaimNote::id)
                .containsExactly(second.id(), first.id());
        assertThat(notes).extracting(ClaimNote::noteType)
                .containsExactly("SETTLEMENT", "DIARY");
        assertThat(notes).extracting(ClaimNote::body)
                .containsExactly("Authority requested", "Called the claimant");
        assertThat(countEvents(claim.id(), "assumed.claim.note-added")).isEqualTo(2);
        assertThat(claimVersion(claim.id())).isEqualTo(2);

        Map<String, Object> event = lastEvent(claim.id());
        assertThat(event.get("event_type")).isEqualTo("assumed.claim.note-added");
        assertThat(aggregateVersion(event)).isEqualTo(3L);
        String notePayload = (String) event.get("payload");
        assertThat(notePayload)
                .contains("\"assumedClaimId\":\"" + claim.id() + "\"")
                .contains("\"noteId\":\"" + second.id() + "\"")
                .contains("\"noteNumber\":\"" + second.noteNumber() + "\"")
                .contains("\"noteType\":\"SETTLEMENT\"")
                .contains("\"body\":\"Authority requested\"")
                .contains("\"author\":\"manager@example.com\"")
                .contains("\"version\":2");
    }

    @Test
    void noteCreateRollbackLeavesNoNoteVersionChangeOrEvent() {
        AssumedClaim claim = createClaim();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            noteService.add(TENANT, claim.id(),
                    new CreateNoteRequest("DIARY", "Called the claimant", "adjuster@example.com"));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(count("claim_note")).isZero();
        assertThat(claimVersion(claim.id())).isZero();
        assertThat(count("outbox_event")).isEqualTo(1);
        assertThat(countEvents(claim.id(), "assumed.claim.note-added")).isZero();
    }

    // ------------------------------------------------------------------ helpers
    private AssumedClaim createClaim() {
        return claimService.create(new CreateAssumedClaimRequest(
                TENANT, CONTRACT, "CED-TSK-1", LOSS_DATE, REPORT_DATE,
                new BigDecimal("100000.00"), "USD"), "adjuster");
    }

    private ClaimTask createTask(AssumedClaim claim, String title, LocalDate dueDate, String priority) {
        return taskService.create(TENANT, claim.id(),
                new CreateTaskRequest(title, null, dueDate, priority, "adjuster@example.com"));
    }

    private int claimVersion(UUID claimId) {
        return jdbc.queryForObject("SELECT version FROM assumed_claim WHERE id = ?", Integer.class,
                claimId);
    }

    private String taskStatus(UUID taskId) {
        return jdbc.queryForObject("SELECT status FROM claim_task WHERE id = ?", String.class, taskId);
    }

    private long taskVersion(UUID taskId) {
        Long value = jdbc.queryForObject("SELECT version FROM claim_task WHERE id = ?", Long.class,
                taskId);
        return value == null ? 0L : value;
    }

    private List<Map<String, Object>> events(UUID claimId) {
        return jdbc.queryForList("""
                SELECT event_type, aggregate_type, aggregate_version, payload, headers, tenant_id,
                       partition_key
                  FROM outbox_event
                 WHERE aggregate_id = ?
                 ORDER BY event_sequence
                """, claimId);
    }

    private Map<String, Object> lastEvent(UUID claimId) {
        List<Map<String, Object>> events = events(claimId);
        assertThat(events).isNotEmpty();
        return events.get(events.size() - 1);
    }

    private int countEvents(UUID claimId, String eventType) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = ?
                """, Integer.class, claimId, eventType);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static long aggregateVersion(Map<String, Object> event) {
        return ((Number) event.get("aggregate_version")).longValue();
    }
}
