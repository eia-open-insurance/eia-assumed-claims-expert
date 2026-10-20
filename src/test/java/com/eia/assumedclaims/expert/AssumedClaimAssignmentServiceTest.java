package com.eia.assumedclaims.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit invariants for claim assignment: the optimistic version gate, the
 * same-assignment no-op, validation, and the event written in the command.
 */
@ExtendWith(MockitoExtension.class)
class AssumedClaimAssignmentServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID CLAIM = UUID.fromString("0192f3a4-5b1c-7e2d-9a3f-6c8d1e0b4a72");
    private static final UUID CONTRACT = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private AssumedClaimRepository claims;
    @Mock
    private ReserveRepository reserves;
    @Mock
    private LossAdviceRepository advices;
    @Mock
    private CashCallRepository cashCalls;
    @Mock
    private DisputeRepository disputes;
    @Mock
    private ClaimAuditRepository audit;
    @Mock
    private OutboxRepository outbox;

    private AssumedClaimService service;

    @BeforeEach
    void setUp() {
        service = new AssumedClaimService(
                claims, reserves, advices, cashCalls, disputes, audit, outbox, CLOCK);
    }

    @Test
    void assignsAnUnassignedClaimAndWritesTheAuditAndEvent() {
        AssumedClaim current = claim(null, null, 1);
        AssumedClaim updated = claim("A. Adjuster", "CASUALTY", 2);
        given(claims.findById(TENANT, CLAIM)).willReturn(Optional.of(current));
        given(claims.assign(TENANT, CLAIM, "A. Adjuster", "CASUALTY", "adjuster@example.com", NOW, 1))
                .willReturn(Optional.of(updated));

        AssumedClaim result = service.assign(TENANT, CLAIM,
                new ClaimAssignmentRequest("  A. Adjuster  ", " CASUALTY ", 1),
                "adjuster@example.com");

        assertEquals("A. Adjuster", result.assignedAdjuster());
        assertEquals("CASUALTY", result.assignedTeam());
        assertEquals(NOW, result.assignedAt());
        assertEquals(2, result.version());
        verify(audit).append(eq(TENANT), eq(CLAIM), eq(AuditEventType.CLAIM_ASSIGNED),
                eq("adjuster@example.com"), eq(
                        "Assigned RNS-2026-0007731 to A. Adjuster (CASUALTY) from unassigned"));

        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(event.capture());
        assertEquals("assumed.claim.assigned", event.getValue().eventType());
        assertEquals(3L, event.getValue().aggregateVersion());
        assertEquals(CLAIM, event.getValue().aggregateId());
        assertEquals(TENANT, event.getValue().tenantId());
        assertEquals(CLAIM.toString(), event.getValue().partitionKey());
        assertEquals(true, event.getValue().payload().contains("\"assignedAdjuster\":\"A. Adjuster\""));
        assertEquals(true, event.getValue().payload().contains("\"assignedTeam\":\"CASUALTY\""));
        assertEquals(true, event.getValue().payload().contains("\"assignedAt\":\"2026-10-20T12:00:00Z\""));
        assertEquals(true, event.getValue().payload().contains("\"assignedBy\":\"adjuster@example.com\""));
        assertEquals(true, event.getValue().payload().contains("\"version\":2"));
        assertEquals(false, event.getValue().payload().contains("previousAssignedAdjuster"));
    }

    @Test
    void reassignsAndCarriesThePreviousOwnerInTheEvent() {
        AssumedClaim current = claim("A. Adjuster", "CASUALTY", 4);
        AssumedClaim updated = claim("B. Examiner", "PROPERTY", 5);
        given(claims.findById(TENANT, CLAIM)).willReturn(Optional.of(current));
        given(claims.assign(TENANT, CLAIM, "B. Examiner", "PROPERTY", "lead@example.com", NOW, 4))
                .willReturn(Optional.of(updated));

        AssumedClaim result = service.assign(TENANT, CLAIM,
                new ClaimAssignmentRequest("B. Examiner", "PROPERTY", 4), "lead@example.com");

        assertEquals("B. Examiner", result.assignedAdjuster());
        assertEquals("PROPERTY", result.assignedTeam());
        assertEquals(5, result.version());

        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(event.capture());
        assertEquals(6L, event.getValue().aggregateVersion());
        assertEquals(true, event.getValue().payload()
                .contains("\"previousAssignedAdjuster\":\"A. Adjuster\""));
        assertEquals(true, event.getValue().payload()
                .contains("\"previousAssignedTeam\":\"CASUALTY\""));
        assertEquals(true, event.getValue().payload()
                .contains("\"changedFields\":[\"assignedAdjuster\",\"assignedTeam\"]"));
    }

    @Test
    void aStaleExpectedVersionIsRejectedBeforeAnyWrite() {
        given(claims.findById(TENANT, CLAIM)).willReturn(Optional.of(claim("A. Adjuster", "CASUALTY", 3)));

        ApiException exception = assertThrows(ApiException.class, () -> service.assign(
                TENANT, CLAIM, new ClaimAssignmentRequest("B. Examiner", "PROPERTY", 2), "lead"));

        assertEquals("EIA-RNS-7006", exception.code());
        assertEquals(409, exception.status().value());
        verify(claims, never()).assign(any(), any(), any(), any(), any(), any(), anyInt());
        verifyNoInteractions(audit, outbox);
    }

    @Test
    void repeatingTheCurrentAssignmentIsANoOp() {
        AssumedClaim current = claim("A. Adjuster", "CASUALTY", 3);
        given(claims.findById(TENANT, CLAIM)).willReturn(Optional.of(current));

        AssumedClaim result = service.assign(TENANT, CLAIM,
                new ClaimAssignmentRequest("A. Adjuster", "CASUALTY", 3), "adjuster@example.com");

        assertSame(current, result);
        assertEquals(3, result.version());
        verify(claims, never()).assign(any(), any(), any(), any(), any(), any(), anyInt());
        verifyNoInteractions(audit, outbox);
    }

    @Test
    void aBlankAdjusterIsRejected() {
        given(claims.findById(TENANT, CLAIM)).willReturn(Optional.of(claim(null, null, 1)));

        ApiException adjuster = assertThrows(ApiException.class, () -> service.assign(
                TENANT, CLAIM, new ClaimAssignmentRequest("  ", "CASUALTY", 1), "lead"));

        assertEquals("EIA-RNS-7004", adjuster.code());
        assertEquals(400, adjuster.status().value());
        assertEquals("assignedAdjuster", adjuster.errors().get(0).field());
        verify(claims, never()).assign(any(), any(), any(), any(), any(), any(), anyInt());
        verifyNoInteractions(audit, outbox);
    }

    @Test
    void anOverlongTeamIsRejected() {
        given(claims.findById(TENANT, CLAIM)).willReturn(Optional.of(claim(null, null, 1)));

        ApiException team = assertThrows(ApiException.class, () -> service.assign(
                TENANT, CLAIM, new ClaimAssignmentRequest("A. Adjuster", "T".repeat(65), 1), "lead"));

        assertEquals("EIA-RNS-7004", team.code());
        assertEquals("assignedTeam", team.errors().get(0).field());
        verify(claims, never()).assign(any(), any(), any(), any(), any(), any(), anyInt());
        verifyNoInteractions(audit, outbox);
    }

    @Test
    void aTeamIsOptional() {
        AssumedClaim current = claim(null, null, 1);
        AssumedClaim updated = claim("A. Adjuster", null, 2);
        given(claims.findById(TENANT, CLAIM)).willReturn(Optional.of(current));
        given(claims.assign(TENANT, CLAIM, "A. Adjuster", null, "lead", NOW, 1))
                .willReturn(Optional.of(updated));

        AssumedClaim result = service.assign(TENANT, CLAIM,
                new ClaimAssignmentRequest("A. Adjuster", "   ", 1), "lead");

        assertEquals("A. Adjuster", result.assignedAdjuster());
        assertNull(result.assignedTeam());
        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(event.capture());
        assertEquals(false, event.getValue().payload().contains("\"assignedTeam\""));
    }

    @Test
    void aMissingActorFallsBackToTheAssignedAdjuster() {
        AssumedClaim current = claim(null, null, 1);
        AssumedClaim updated = claim("A. Adjuster", "CASUALTY", 2);
        given(claims.findById(TENANT, CLAIM)).willReturn(Optional.of(current));
        given(claims.assign(TENANT, CLAIM, "A. Adjuster", "CASUALTY", "A. Adjuster", NOW, 1))
                .willReturn(Optional.of(updated));

        service.assign(TENANT, CLAIM,
                new ClaimAssignmentRequest("A. Adjuster", "CASUALTY", 1), null);

        verify(audit).append(TENANT, CLAIM, AuditEventType.CLAIM_ASSIGNED, "A. Adjuster",
                "Assigned RNS-2026-0007731 to A. Adjuster (CASUALTY) from unassigned");
        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(event.capture());
        assertEquals(true, event.getValue().payload().contains("\"assignedBy\":\"A. Adjuster\""));
    }

    @Test
    void aMissingOrNegativeExpectedVersionIsRejected() {
        given(claims.findById(TENANT, CLAIM)).willReturn(Optional.of(claim(null, null, 1)));

        ApiException missing = assertThrows(ApiException.class, () -> service.assign(
                TENANT, CLAIM, new ClaimAssignmentRequest("A. Adjuster", "CASUALTY", null), "lead"));
        assertEquals("EIA-RNS-7004", missing.code());
        assertEquals("expectedVersion", missing.errors().get(0).field());

        ApiException negative = assertThrows(ApiException.class, () -> service.assign(
                TENANT, CLAIM, new ClaimAssignmentRequest("A. Adjuster", "CASUALTY", -1), "lead"));
        assertEquals("EIA-RNS-7004", negative.code());
    }

    @Test
    void aConcurrentVersionChangeMapsToTheStandardConflict() {
        given(claims.findById(TENANT, CLAIM)).willReturn(Optional.of(claim(null, null, 2)));
        given(claims.assign(TENANT, CLAIM, "A. Adjuster", "CASUALTY", "lead", NOW, 2))
                .willReturn(Optional.empty());

        ApiException exception = assertThrows(ApiException.class, () -> service.assign(
                TENANT, CLAIM, new ClaimAssignmentRequest("A. Adjuster", "CASUALTY", 2), "lead"));

        assertEquals("EIA-RNS-7006", exception.code());
        verifyNoInteractions(audit, outbox);
    }

    @Test
    void anUnassignedClaimHasNullAssignmentFields() {
        AssumedClaim current = claim(null, null, 0);

        assertNull(current.assignedAdjuster());
        assertNull(current.assignedTeam());
        assertNull(current.assignedAt());
    }

    @Test
    void aMissingClaimIs404() {
        given(claims.findById(TENANT, CLAIM)).willReturn(Optional.empty());

        ApiException exception = assertThrows(ApiException.class, () -> service.assign(
                TENANT, CLAIM, new ClaimAssignmentRequest("A. Adjuster", "CASUALTY", 1), "lead"));

        assertEquals("EIA-RNS-7001", exception.code());
        assertEquals(404, exception.status().value());
        verifyNoInteractions(audit, outbox);
    }

    private static AssumedClaim claim(String adjuster, String team, int version) {
        Instant assignedAt = adjuster == null ? null : NOW;
        return new AssumedClaim(
                CLAIM,
                TENANT,
                "ASSUMED",
                "RNS-2026-0007731",
                CONTRACT,
                "CED-778",
                AssumedClaimStatus.OPEN,
                LocalDate.parse("2026-09-01"),
                LocalDate.parse("2026-09-02"),
                new BigDecimal("12500.0000"),
                "USD",
                adjuster,
                team,
                assignedAt,
                version,
                NOW,
                NOW);
    }
}
