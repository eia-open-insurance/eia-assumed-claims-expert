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
import static com.eia.assumedclaims.expert.TaskNoteFixtures.NOTE_ID;
import static com.eia.assumedclaims.expert.TaskNoteFixtures.NOW;
import static com.eia.assumedclaims.expert.TaskNoteFixtures.TENANT;
import static com.eia.assumedclaims.expert.TaskNoteFixtures.claim;
import static com.eia.assumedclaims.expert.TaskNoteFixtures.note;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaimNoteServiceTest {
    @Mock
    private ClaimNoteRepository repository;
    @Mock
    private AssumedClaimService claimService;
    @Mock
    private AssumedClaimRepository claims;
    @Mock
    private OutboxRepository outbox;

    private ClaimNoteService service;

    @BeforeEach
    void setUp() {
        service = new ClaimNoteService(repository, claimService, claims, outbox,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void listsNotesForTheClaim() {
        claimExists(0);
        List<ClaimNote> notes = List.of(note("DIARY", "Called the claimant"));
        when(repository.findAll(TENANT, CLAIM_ID)).thenReturn(notes);

        List<ClaimNote> found = service.list(TENANT, CLAIM_ID);

        assertSame(notes, found);
        verifyNoInteractions(outbox);
    }

    @Test
    void addsANoteAndWritesTheNoteAddedEvent() {
        claimExists(0);
        when(repository.nextNoteSequence()).thenReturn(1L);
        when(repository.insert(any(UUID.class), eq(TENANT), eq(CLAIM_ID), eq("NTE-2026-0000001"),
                eq("DIARY"), eq("Called the claimant"), eq("adjuster@example.com"),
                any(Instant.class))).thenReturn(note("DIARY", "Called the claimant"));

        ClaimNote added = service.add(TENANT, CLAIM_ID,
                new CreateNoteRequest("DIARY", "Called the claimant", "adjuster@example.com"));

        assertEquals("DIARY", added.noteType());
        assertEquals("NTE-2026-0000001", added.noteNumber());
        verify(claims).touch(TENANT, CLAIM_ID);

        OutboxEvent event = capturedEvent();
        assertEquals(AssumedClaimEvents.NOTE_ADDED, event.eventType());
        assertEquals("AssumedClaim", event.aggregateType());
        assertEquals(CLAIM_ID, event.aggregateId());
        assertEquals(2L, event.aggregateVersion());

        Map<String, Object> payload = payload(event);
        assertEquals(CLAIM_ID.toString(), payload.get("assumedClaimId"));
        assertEquals(NOTE_ID.toString(), payload.get("noteId"));
        assertEquals("NTE-2026-0000001", payload.get("noteNumber"));
        assertEquals("DIARY", payload.get("noteType"));
        assertEquals("Called the claimant", payload.get("body"));
        assertEquals("adjuster@example.com", payload.get("author"));
        assertEquals(1, payload.get("version"));
    }

    @Test
    void rejectsAnUnknownNoteType() {
        claimExists(0);

        ApiException error = assertThrows(ApiException.class,
                () -> service.add(TENANT, CLAIM_ID,
                        new CreateNoteRequest("EMAIL", "Called the claimant", "adjuster@example.com")));

        assertEquals(HttpStatus.BAD_REQUEST, error.status());
        assertTrue(error.getMessage().contains("Unknown noteType"));
        verify(repository, never()).insert(any(), any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(outbox);
    }

    @Test
    void rejectsAnEmptyBody() {
        claimExists(0);

        ApiException error = assertThrows(ApiException.class,
                () -> service.add(TENANT, CLAIM_ID,
                        new CreateNoteRequest("DIARY", "   ", "adjuster@example.com")));

        assertTrue(error.getMessage().contains("body is required"));
        verifyNoInteractions(outbox);
    }

    @Test
    void requiresAnAuthor() {
        claimExists(0);

        ApiException error = assertThrows(ApiException.class,
                () -> service.add(TENANT, CLAIM_ID,
                        new CreateNoteRequest("DIARY", "Called the claimant", " ")));

        assertTrue(error.getMessage().contains("author is required"));
        verifyNoInteractions(outbox);
    }

    @Test
    void aClaimOutsideTheTenantIsNotFound() {
        when(claimService.requireClaim(TENANT, CLAIM_ID))
                .thenThrow(ApiException.assumedClaimNotFound(CLAIM_ID));

        ApiException error = assertThrows(ApiException.class,
                () -> service.list(TENANT, CLAIM_ID));

        assertEquals(HttpStatus.NOT_FOUND, error.status());
        assertEquals("EIA-RNS-7001", error.code());
        verifyNoInteractions(outbox);
    }

    private void claimExists(int version) {
        when(claimService.requireClaim(TENANT, CLAIM_ID))
                .thenReturn(claim(AssumedClaimStatus.NOTIFIED, version));
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
