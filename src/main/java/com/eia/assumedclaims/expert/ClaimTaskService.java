package com.eia.assumedclaims.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Claim task commands and queries.
 *
 * <p>A task write is part of the Assumed Claim aggregate transaction: it bumps
 * the parent claim version (keeping the per-claim event stream monotonic) and
 * appends its event to the outbox in the same transaction. The task's own
 * version is the optimistic concurrency token exposed by PATCH; a stale token
 * is 409 and a command that repeats the current status is a no-op that writes
 * no event.</p>
 */
@Service
public class ClaimTaskService {
    private final ClaimTaskRepository repository;
    private final AssumedClaimService claimService;
    private final AssumedClaimRepository claims;
    private final OutboxRepository outbox;
    private final Clock clock;

    @Autowired
    public ClaimTaskService(ClaimTaskRepository repository,
                            AssumedClaimService claimService,
                            AssumedClaimRepository claims,
                            OutboxRepository outbox) {
        this(repository, claimService, claims, outbox, Clock.systemUTC());
    }

    ClaimTaskService(ClaimTaskRepository repository,
                     AssumedClaimService claimService,
                     AssumedClaimRepository claims,
                     OutboxRepository outbox,
                     Clock clock) {
        this.repository = repository;
        this.claimService = claimService;
        this.claims = claims;
        this.outbox = outbox;
        this.clock = clock;
    }

    public List<ClaimTask> list(UUID tenantId, UUID claimId) {
        claimService.requireClaim(tenantId, claimId);
        return repository.findAll(tenantId, claimId);
    }

    @Transactional
    public ClaimTask create(UUID tenantId, UUID claimId, CreateTaskRequest request) {
        AssumedClaim claim = claimService.requireClaim(tenantId, claimId);
        String title = TaskNoteVocab.requiredText(request.title(), "title", 200);
        String description = TaskNoteVocab.optionalText(request.description(), "description", 4000);
        LocalDate dueDate = TaskNoteVocab.requiredDate(request.dueDate(), "dueDate");
        String priority = TaskNoteVocab.normalize(TaskNoteVocab.TASK_PRIORITIES, request.priority(),
                "priority");
        String assignedTo = TaskNoteVocab.optionalText(request.assignedTo(), "assignedTo", 120);

        Instant now = Instant.now(clock);
        String taskNumber = String.format("TSK-%d-%07d", LocalDate.now(clock).getYear(),
                repository.nextTaskSequence());
        ClaimTask task = repository.insert(UuidV7.generate(), tenantId, claimId, taskNumber, title,
                description, dueDate, priority, assignedTo, now);
        claims.touch(tenantId, claimId);
        outbox.insert(OutboxEvent.fromEnvelope(AssumedClaimEvents.taskCreated(claim, task, now)));
        return task;
    }

    @Transactional
    public ClaimTask changeStatus(UUID tenantId,
                                  UUID claimId,
                                  UUID taskId,
                                  TaskStatusChangeRequest request) {
        AssumedClaim claim = claimService.requireClaim(tenantId, claimId);
        ClaimTask current = repository.findById(tenantId, claimId, taskId)
                .orElseThrow(() -> ApiException.taskNotFound(taskId));
        String next = TaskNoteVocab.normalize(TaskNoteVocab.TASK_STATUSES, request.status(), "status");
        if (request.version() == null) {
            throw ApiException.validation("version is required", List.of(
                    new FieldIssue("version", "REQUIRED", "version is required")));
        }
        long expected = request.version();
        if (expected != current.version()) {
            throw ApiException.taskVersionConflict(expected);
        }
        if (next.equals(current.status())) {
            return current;
        }
        requireTransition(current.status(), next);

        Instant now = Instant.now(clock);
        claims.touch(tenantId, claimId);
        ClaimTask after = repository.updateStatus(tenantId, claimId, taskId, next, expected, now)
                .orElseThrow(() -> ApiException.taskVersionConflict(expected));
        outbox.insert(OutboxEvent.fromEnvelope(
                AssumedClaimEvents.taskStatusChanged(claim, current, after, now)));
        return after;
    }

    /**
     * A task may be completed or cancelled while open, and either state may be
     * reopened. Cancelling a completed task (or completing a cancelled one)
     * requires an explicit reopen first.
     */
    private static void requireTransition(String current, String next) {
        boolean allowed = switch (current) {
            case "OPEN" -> next.equals("COMPLETE") || next.equals("CANCELLED");
            case "COMPLETE", "CANCELLED" -> next.equals("OPEN");
            default -> false;
        };
        if (!allowed) {
            throw ApiException.validation(
                    "Illegal claim task status transition " + current + " -> " + next, List.of(
                            new FieldIssue("status", "ILLEGAL_TASK_TRANSITION",
                                    current + " cannot transition to " + next)));
        }
    }
}
