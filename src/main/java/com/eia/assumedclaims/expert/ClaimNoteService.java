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
import java.util.function.Supplier;

/**
 * Append-only claim notes.
 *
 * <p>There is deliberately no update or delete command. Adding a note bumps the
 * parent claim version and writes {@code assumed.claim.note-added} in the same
 * transaction, so a rollback leaves neither the note nor the event behind.</p>
 *
 * <p>Create accepts an optional {@code Idempotency-Key}. A same-key retry with
 * an identical canonical body returns the original 201 projection without
 * appending a second note or event.</p>
 */
@Service
public class ClaimNoteService {
    private final ClaimNoteRepository repository;
    private final AssumedClaimService claimService;
    private final AssumedClaimRepository claims;
    private final OutboxRepository outbox;
    private final ClaimCommandIdempotency idempotency;
    private final Clock clock;

    @Autowired
    public ClaimNoteService(ClaimNoteRepository repository,
                            AssumedClaimService claimService,
                            AssumedClaimRepository claims,
                            OutboxRepository outbox,
                            ClaimCommandIdempotency idempotency) {
        this(repository, claimService, claims, outbox, idempotency, Clock.systemUTC());
    }

    ClaimNoteService(ClaimNoteRepository repository,
                     AssumedClaimService claimService,
                     AssumedClaimRepository claims,
                     OutboxRepository outbox,
                     ClaimCommandIdempotency idempotency,
                     Clock clock) {
        this.repository = repository;
        this.claimService = claimService;
        this.claims = claims;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    public List<ClaimNote> list(UUID tenantId, UUID claimId) {
        claimService.requireClaim(tenantId, claimId);
        return repository.findAll(tenantId, claimId);
    }

    @Transactional
    public CommandResult<ClaimNote> add(UUID tenantId,
                                        UUID claimId,
                                        CreateNoteRequest request,
                                        String idempotencyKey) {
        String key = IdempotencyKeys.normalize(idempotencyKey);
        AssumedClaim claim = claimService.requireClaim(tenantId, claimId);
        String noteType = TaskNoteVocab.normalize(TaskNoteVocab.NOTE_TYPES, request.noteType(),
                "noteType");
        String body = TaskNoteVocab.requiredText(request.body(), "body", 8000);
        String author = TaskNoteVocab.requiredText(request.author(), "author", 120);

        Instant now = Instant.now(clock);
        Supplier<ClaimNote> action = () -> insertNote(tenantId, claimId, claim, noteType, body,
                author, now);
        if (key == null) {
            return new CommandResult<>(action.get(), false);
        }
        String requestHash = IdempotencyKeys.noteRequestHash(claimId, noteType, body, author);
        return idempotency.run(tenantId, ClaimCommandIdempotency.NOTE_CREATE, key, requestHash,
                claimId, now, ClaimNote.class, action);
    }

    private ClaimNote insertNote(UUID tenantId,
                                 UUID claimId,
                                 AssumedClaim claim,
                                 String noteType,
                                 String body,
                                 String author,
                                 Instant now) {
        String noteNumber = String.format("NTE-%d-%07d", LocalDate.now(clock).getYear(),
                repository.nextNoteSequence());
        ClaimNote note = repository.insert(UuidV7.generate(), tenantId, claimId, noteNumber,
                noteType, body, author, now);
        claims.touch(tenantId, claimId);
        outbox.insert(OutboxEvent.fromEnvelope(AssumedClaimEvents.noteAdded(claim, note, now)));
        return note;
    }
}
