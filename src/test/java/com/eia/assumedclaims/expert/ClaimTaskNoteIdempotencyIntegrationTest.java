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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end idempotency for claim task and note creation against the H2 test
 * schema. A retry with the same key and canonical body returns the original 201
 * projection without a second row, claim version bump, or event; reuse with a
 * different body is 409 {@code IDEMPOTENCY_KEY_REUSED}; a missing key keeps the
 * previous append behaviour; and the reservation is tenant- and command-scoped
 * and rolls back with the command.
 */
@SpringBootTest
@ActiveProfiles("test")
class ClaimTaskNoteIdempotencyIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID CONTRACT = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final LocalDate LOSS_DATE = LocalDate.parse("2026-09-01");
    private static final LocalDate REPORT_DATE = LocalDate.parse("2026-09-02");
    private static final LocalDate DUE_DATE = LocalDate.parse("2026-04-15");

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
        jdbc.update("DELETE FROM claim_command_idempotency");
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
    void taskReplayReturnsTheOriginalProjectionAndWritesNoDuplicateRowOrEvent() {
        AssumedClaim claim = createClaim(TENANT);
        CreateTaskRequest request = taskRequest("Review medical records", "HIGH");

        CommandResult<ClaimTask> first = taskService.create(TENANT, claim.id(), request,
                "task-key-1");
        CommandResult<ClaimTask> replay = taskService.create(TENANT, claim.id(), request,
                "task-key-1");

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body().id()).isEqualTo(first.body().id());
        assertThat(replay.body().taskNumber()).isEqualTo(first.body().taskNumber());
        assertThat(replay.body().createdAt()).isEqualTo(first.body().createdAt());
        assertThat(replay.body().status()).isEqualTo("OPEN");
        assertThat(rowCount("claim_task")).isEqualTo(1);
        assertThat(rowCount("claim_command_idempotency")).isEqualTo(1);
        assertThat(countEvents(claim.id(), AssumedClaimEvents.TASK_CREATED)).isEqualTo(1);
        assertThat(claimVersion(claim.id())).isEqualTo(1);
        assertThat(storedResponseBody(ClaimCommandIdempotency.TASK_CREATE, "task-key-1"))
                .contains("\"taskNumber\":\"TSK-")
                .contains("\"id\":\"" + first.body().id() + "\"");

        // The replay did not consume a task number: the next fresh task is one sequence step on.
        CommandResult<ClaimTask> next = taskService.create(TENANT, claim.id(), request,
                "task-key-2");
        assertThat(next.replayed()).isFalse();
        assertThat(sequenceOf(next.body().taskNumber()))
                .isEqualTo(sequenceOf(first.body().taskNumber()) + 1);
        assertThat(rowCount("claim_task")).isEqualTo(2);
    }

    @Test
    void taskKeyReusedWithADifferentBodyIs409AndWritesNothing() {
        AssumedClaim claim = createClaim(TENANT);
        taskService.create(TENANT, claim.id(), taskRequest("Review medical records", "HIGH"),
                "task-key-1");

        ApiException error = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> taskService.create(TENANT, claim.id(),
                        taskRequest("Review medical records", "URGENT"), "task-key-1"));

        assertThat(error.status().value()).isEqualTo(409);
        assertThat(error.code()).isEqualTo("EIA-RNS-4091");
        assertThat(error.category()).isEqualTo("CONFLICT");
        assertThat(error.errors()).singleElement().satisfies(issue -> {
            assertThat(issue.field()).isEqualTo("Idempotency-Key");
            assertThat(issue.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        });
        assertThat(rowCount("claim_task")).isEqualTo(1);
        assertThat(rowCount("claim_command_idempotency")).isEqualTo(1);
        assertThat(countEvents(claim.id(), AssumedClaimEvents.TASK_CREATED)).isEqualTo(1);
        assertThat(claimVersion(claim.id())).isEqualTo(1);
        assertThat(storedResponseBody(ClaimCommandIdempotency.TASK_CREATE, "task-key-1"))
                .contains("\"priority\":\"HIGH\"");
    }

    @Test
    void aMissingTaskKeyKeepsTheCurrentCreateBehaviour() {
        AssumedClaim claim = createClaim(TENANT);
        CreateTaskRequest request = taskRequest("Review medical records", "HIGH");

        CommandResult<ClaimTask> first = taskService.create(TENANT, claim.id(), request, null);
        CommandResult<ClaimTask> second = taskService.create(TENANT, claim.id(), request, null);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isFalse();
        assertThat(second.body().id()).isNotEqualTo(first.body().id());
        assertThat(rowCount("claim_task")).isEqualTo(2);
        assertThat(rowCount("claim_command_idempotency")).isZero();
        assertThat(countEvents(claim.id(), AssumedClaimEvents.TASK_CREATED)).isEqualTo(2);
        assertThat(claimVersion(claim.id())).isEqualTo(2);
    }

    @Test
    void taskRollbackLeavesNoReservationSoTheKeyCanBeRetried() {
        AssumedClaim claim = createClaim(TENANT);
        CreateTaskRequest request = taskRequest("Review medical records", "HIGH");

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            taskService.create(TENANT, claim.id(), request, "rollback-key");
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(rowCount("claim_task")).isZero();
        assertThat(rowCount("claim_command_idempotency")).isZero();
        assertThat(countEvents(claim.id(), AssumedClaimEvents.TASK_CREATED)).isZero();
        assertThat(claimVersion(claim.id())).isZero();

        CommandResult<ClaimTask> retried = taskService.create(TENANT, claim.id(), request,
                "rollback-key");
        assertThat(retried.replayed()).isFalse();
        assertThat(rowCount("claim_task")).isEqualTo(1);
        assertThat(countEvents(claim.id(), AssumedClaimEvents.TASK_CREATED)).isEqualTo(1);
    }

    // -------------------------------------------------------------------- notes
    @Test
    void noteReplayReturnsTheOriginalProjectionAndWritesNoDuplicateRowOrEvent() {
        AssumedClaim claim = createClaim(TENANT);
        CreateNoteRequest request = new CreateNoteRequest("DIARY", "Called the claimant",
                "adjuster@example.com");

        CommandResult<ClaimNote> first = noteService.add(TENANT, claim.id(), request, "note-key-1");
        CommandResult<ClaimNote> replay = noteService.add(TENANT, claim.id(), request,
                "note-key-1");

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body().id()).isEqualTo(first.body().id());
        assertThat(replay.body().noteNumber()).isEqualTo(first.body().noteNumber());
        assertThat(replay.body().createdAt()).isEqualTo(first.body().createdAt());
        assertThat(rowCount("claim_note")).isEqualTo(1);
        assertThat(rowCount("claim_command_idempotency")).isEqualTo(1);
        assertThat(countEvents(claim.id(), AssumedClaimEvents.NOTE_ADDED)).isEqualTo(1);
        assertThat(claimVersion(claim.id())).isEqualTo(1);
        assertThat(storedResponseBody(ClaimCommandIdempotency.NOTE_CREATE, "note-key-1"))
                .contains("\"noteNumber\":\"NTE-")
                .contains("\"body\":\"Called the claimant\"");
    }

    @Test
    void noteKeyReusedWithADifferentBodyIs409AndWritesNothing() {
        AssumedClaim claim = createClaim(TENANT);
        noteService.add(TENANT, claim.id(),
                new CreateNoteRequest("DIARY", "Called the claimant", "adjuster@example.com"),
                "note-key-1");

        ApiException error = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> noteService.add(TENANT, claim.id(),
                        new CreateNoteRequest("DIARY", "A different note", "adjuster@example.com"),
                        "note-key-1"));

        assertThat(error.status().value()).isEqualTo(409);
        assertThat(error.code()).isEqualTo("EIA-RNS-4091");
        assertThat(error.errors()).singleElement()
                .satisfies(issue -> assertThat(issue.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThat(rowCount("claim_note")).isEqualTo(1);
        assertThat(countEvents(claim.id(), AssumedClaimEvents.NOTE_ADDED)).isEqualTo(1);
        assertThat(claimVersion(claim.id())).isEqualTo(1);
    }

    @Test
    void aMissingNoteKeyKeepsTheCurrentAppendBehaviour() {
        AssumedClaim claim = createClaim(TENANT);
        CreateNoteRequest request = new CreateNoteRequest("DIARY", "Called the claimant",
                "adjuster@example.com");

        CommandResult<ClaimNote> first = noteService.add(TENANT, claim.id(), request, null);
        CommandResult<ClaimNote> second = noteService.add(TENANT, claim.id(), request, null);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isFalse();
        assertThat(second.body().id()).isNotEqualTo(first.body().id());
        assertThat(rowCount("claim_note")).isEqualTo(2);
        assertThat(rowCount("claim_command_idempotency")).isZero();
        assertThat(countEvents(claim.id(), AssumedClaimEvents.NOTE_ADDED)).isEqualTo(2);
    }

    @Test
    void noteRollbackLeavesNoReservationSoTheKeyCanBeRetried() {
        AssumedClaim claim = createClaim(TENANT);
        CreateNoteRequest request = new CreateNoteRequest("DIARY", "Called the claimant",
                "adjuster@example.com");

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            noteService.add(TENANT, claim.id(), request, "rollback-note-key");
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(rowCount("claim_note")).isZero();
        assertThat(rowCount("claim_command_idempotency")).isZero();
        assertThat(countEvents(claim.id(), AssumedClaimEvents.NOTE_ADDED)).isZero();
        assertThat(claimVersion(claim.id())).isZero();

        CommandResult<ClaimNote> retried = noteService.add(TENANT, claim.id(), request,
                "rollback-note-key");
        assertThat(retried.replayed()).isFalse();
        assertThat(rowCount("claim_note")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ scoping
    @Test
    void aKeyIsScopedByTenant() {
        AssumedClaim tenantClaim = createClaim(TENANT);
        AssumedClaim otherClaim = createClaim(OTHER_TENANT);
        CreateTaskRequest request = taskRequest("Review medical records", "HIGH");

        CommandResult<ClaimTask> mine = taskService.create(TENANT, tenantClaim.id(), request,
                "shared-key");
        CommandResult<ClaimTask> theirs = taskService.create(OTHER_TENANT, otherClaim.id(),
                request, "shared-key");

        assertThat(mine.replayed()).isFalse();
        assertThat(theirs.replayed()).isFalse();
        assertThat(theirs.body().id()).isNotEqualTo(mine.body().id());
        assertThat(rowCount("claim_task")).isEqualTo(2);
        assertThat(rowCount("claim_command_idempotency")).isEqualTo(2);

        // The same key does not leak across tenants: the other tenant cannot see the claim.
        assertThatThrownBy(() -> taskService.create(OTHER_TENANT, tenantClaim.id(), request,
                "shared-key")).isInstanceOf(ApiException.class)
                .hasMessageContaining("was not found");
        assertThat(rowCount("claim_task")).isEqualTo(2);
        assertThat(rowCount("claim_command_idempotency")).isEqualTo(2);
    }

    @Test
    void aKeyIsScopedByCommandType() {
        AssumedClaim claim = createClaim(TENANT);
        CreateTaskRequest taskRequest = taskRequest("Review medical records", "HIGH");
        CreateNoteRequest noteRequest = new CreateNoteRequest("DIARY", "Called the claimant",
                "adjuster@example.com");

        CommandResult<ClaimTask> task = taskService.create(TENANT, claim.id(), taskRequest,
                "shared-command-key");
        CommandResult<ClaimNote> note = noteService.add(TENANT, claim.id(), noteRequest,
                "shared-command-key");

        assertThat(task.replayed()).isFalse();
        assertThat(note.replayed()).isFalse();
        assertThat(rowCount("claim_task")).isEqualTo(1);
        assertThat(rowCount("claim_note")).isEqualTo(1);
        assertThat(rowCount("claim_command_idempotency")).isEqualTo(2);
    }

    @Test
    void aKeyOutsideTheLengthBoundsIs400AndWritesNothing() {
        AssumedClaim claim = createClaim(TENANT);
        CreateTaskRequest request = taskRequest("Review medical records", "HIGH");

        ApiException tooLong = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> taskService.create(TENANT, claim.id(), request, "x".repeat(65)));
        assertThat(tooLong.status().value()).isEqualTo(400);
        assertThat(tooLong.code()).isEqualTo("EIA-RNS-7004");
        assertThat(tooLong.errors()).singleElement().satisfies(issue -> {
            assertThat(issue.field()).isEqualTo("Idempotency-Key");
            assertThat(issue.code()).isEqualTo("INVALID_IDEMPOTENCY_KEY");
        });

        ApiException blank = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> noteService.add(TENANT, claim.id(),
                        new CreateNoteRequest("DIARY", "Called the claimant", "adjuster@example.com"),
                        "   "));
        assertThat(blank.status().value()).isEqualTo(400);
        assertThat(blank.errors()).singleElement()
                .satisfies(issue -> assertThat(issue.code()).isEqualTo("INVALID_IDEMPOTENCY_KEY"));

        assertThat(rowCount("claim_command_idempotency")).isZero();
        assertThat(rowCount("claim_task")).isZero();
        assertThat(rowCount("claim_note")).isZero();
    }

    // ------------------------------------------------------------------ helpers
    private AssumedClaim createClaim(UUID tenantId) {
        return claimService.create(new CreateAssumedClaimRequest(
                tenantId, CONTRACT, "CED-IDEM-1", LOSS_DATE, REPORT_DATE,
                new BigDecimal("100000.00"), "USD"), "adjuster");
    }

    private static CreateTaskRequest taskRequest(String title, String priority) {
        return new CreateTaskRequest(title, "Order records and index the file", DUE_DATE, priority,
                "adjuster@example.com");
    }

    private int rowCount(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private int claimVersion(UUID claimId) {
        return jdbc.queryForObject("SELECT version FROM assumed_claim WHERE id = ?", Integer.class,
                claimId);
    }

    private int countEvents(UUID claimId, String eventType) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = ?
                """, Integer.class, claimId, eventType);
    }

    private String storedResponseBody(String commandType, String key) {
        return jdbc.queryForObject("""
                SELECT response_body FROM claim_command_idempotency
                 WHERE tenant_id = ? AND command_type = ? AND idempotency_key = ?
                """, String.class, TENANT, commandType, key);
    }

    private static long sequenceOf(String number) {
        return Long.parseLong(number.substring(number.length() - 7));
    }
}
