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
 * Append-only claim notes.
 *
 * <p>There is deliberately no update or delete command. Adding a note bumps the
 * parent claim version and writes {@code assumed.claim.note-added} in the same
 * transaction, so a rollback leaves neither the note nor the event behind.</p>
 */
@Service
public class ClaimNoteService {
    private final ClaimNoteRepository repository;
    private final AssumedClaimService claimService;
    private final AssumedClaimRepository claims;
    private final OutboxRepository outbox;
    private final Clock clock;

    @Autowired
    public ClaimNoteService(ClaimNoteRepository repository,
                            AssumedClaimService claimService,
                            AssumedClaimRepository claims,
                            OutboxRepository outbox) {
        this(repository, claimService, claims, outbox, Clock.systemUTC());
    }

    ClaimNoteService(ClaimNoteRepository repository,
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

    public List<ClaimNote> list(UUID tenantId, UUID claimId) {
        claimService.requireClaim(tenantId, claimId);
        return repository.findAll(tenantId, claimId);
    }

    @Transactional
    public ClaimNote add(UUID tenantId, UUID claimId, CreateNoteRequest request) {
        AssumedClaim claim = claimService.requireClaim(tenantId, claimId);
        String noteType = TaskNoteVocab.normalize(TaskNoteVocab.NOTE_TYPES, request.noteType(),
                "noteType");
        String body = TaskNoteVocab.requiredText(request.body(), "body", 8000);
        String author = TaskNoteVocab.requiredText(request.author(), "author", 120);

        Instant now = Instant.now(clock);
        String noteNumber = String.format("NTE-%d-%07d", LocalDate.now(clock).getYear(),
                repository.nextNoteSequence());
        ClaimNote note = repository.insert(UuidV7.generate(), tenantId, claimId, noteNumber,
                noteType, body, author, now);
        claims.touch(tenantId, claimId);
        outbox.insert(OutboxEvent.fromEnvelope(AssumedClaimEvents.noteAdded(claim, note, now)));
        return note;
    }
}
