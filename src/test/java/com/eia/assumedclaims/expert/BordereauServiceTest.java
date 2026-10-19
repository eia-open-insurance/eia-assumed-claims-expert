package com.eia.assumedclaims.expert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BordereauServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID CLAIM = UUID.fromString("0192f3a4-5b1c-7e2d-9a3f-6c8d1e0b4a72");
    private static final String REFERENCE = "BDX-2026-01";

    @Mock
    private AssumedClaimService claimService;
    @Mock
    private LossAdviceService adviceService;
    @Mock
    private LossAdviceRepository advices;
    @Mock
    private BordereauRepository bordereaux;
    @Mock
    private ClaimAuditRepository audit;

    private BordereauService service;

    @BeforeEach
    void setUp() {
        service = new BordereauService(claimService, adviceService, advices, bordereaux, audit);
    }

    @Test
    void conflictingReferenceWithDifferentPayloadIsRejected() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim());
        BordereauBatch existing = batch("a".repeat(64));
        given(bordereaux.findByReference(TENANT, REFERENCE)).willReturn(Optional.of(existing));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.ingest(TENANT, CLAIM, request(line("B-1", "100.00")), "adjuster"));

        assertEquals("EIA-RNS-7104", exception.code());
        assertEquals(409, exception.status().value());
        verify(bordereaux, never()).insertPending(any());
    }

    @Test
    void duplicateLinesAreReportedAndNotInserted() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim());
        given(bordereaux.findByReference(TENANT, REFERENCE)).willReturn(Optional.empty());
        given(advices.existsByAdviceNumber(TENANT, CLAIM, "B-1")).willReturn(false);
        given(advices.existsByAdviceNumber(TENANT, CLAIM, "B-2")).willReturn(true);
        given(adviceService.record(eq(TENANT), eq(CLAIM), any(), eq(AdviceSource.BORDEREAU), any(), any(), eq("adjuster")))
                .willReturn(advice("B-1"));
        given(bordereaux.complete(eq(TENANT), any(), any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), any()))
                .willAnswer(invocation -> batch("b".repeat(64)));

        BordereauIngestResult result = service.ingest(
                TENANT, CLAIM, request(line("B-1", "100.00"), line("B-2", "50.00")), "adjuster");

        assertEquals(BordereauLineStatus.ACCEPTED, result.lines().get(0).status());
        assertEquals(BordereauLineStatus.DUPLICATE, result.lines().get(1).status());
        assertEquals(1, result.batch().acceptedCount());
        ArgumentCaptor<Integer> accepted = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> duplicate = ArgumentCaptor.forClass(Integer.class);
        verify(bordereaux).complete(eq(TENANT), any(), any(), any(), eq(2), accepted.capture(),
                duplicate.capture(), eq(0), any());
        assertEquals(1, accepted.getValue());
        assertEquals(1, duplicate.getValue());
        verify(adviceService, times(1)).record(any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void negativeLineIsRejectedWithoutPersistingAnAdvice() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim());
        given(bordereaux.findByReference(TENANT, REFERENCE)).willReturn(Optional.empty());
        given(bordereaux.complete(eq(TENANT), any(), any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), any()))
                .willReturn(batch("c".repeat(64)));

        BordereauIngestResult result = service.ingest(
                TENANT, CLAIM, request(line("B-1", "-1.00")), "adjuster");

        assertEquals(BordereauLineStatus.REJECTED, result.lines().getFirst().status());
        verify(adviceService, never()).record(any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void repostingAnIdenticalReferenceReplaysTheStoredBatch() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim());
        given(bordereaux.findByReference(TENANT, REFERENCE)).willReturn(Optional.empty());
        given(advices.existsByAdviceNumber(TENANT, CLAIM, "B-1")).willReturn(false);
        LossAdvice advice = advice("B-1");
        given(adviceService.record(eq(TENANT), eq(CLAIM), any(), eq(AdviceSource.BORDEREAU), any(), any(), eq("adjuster")))
                .willReturn(advice);
        given(bordereaux.complete(eq(TENANT), any(), any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), any()))
                .willReturn(batch("d".repeat(64)));

        service.ingest(TENANT, CLAIM, request(line("B-1", "100.00")), "adjuster");
        ArgumentCaptor<BordereauBatch> pending = ArgumentCaptor.forClass(BordereauBatch.class);
        verify(bordereaux).insertPending(pending.capture());

        given(bordereaux.findByReference(TENANT, REFERENCE)).willReturn(Optional.of(pending.getValue()));
        given(advices.findByBordereau(TENANT, pending.getValue().id())).willReturn(List.of(advice));

        BordereauIngestResult replay = service.ingest(TENANT, CLAIM, request(line("B-1", "100.00")), "adjuster");

        assertTrue(replay.replayed());
        assertFalse(replay.lines().isEmpty());
        assertEquals(BordereauLineStatus.ACCEPTED, replay.lines().getFirst().status());
        verify(bordereaux, times(1)).complete(any(), any(), any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), any());
    }

    private static IngestBordereauRequest request(IngestBordereauRequest.BordereauLine... lines) {
        return new IngestBordereauRequest(REFERENCE, LocalDate.parse("2026-09-03"), "USD", "CED-778",
                List.of(lines));
    }

    private static IngestBordereauRequest.BordereauLine line(String number, String amount) {
        return new IngestBordereauRequest.BordereauLine(number, LocalDate.parse("2026-09-03"),
                new BigDecimal(amount), null, null, null, AdviceType.UPDATE);
    }

    private static AssumedClaim claim() {
        Instant now = Instant.parse("2026-10-19T12:00:00Z");
        return new AssumedClaim(CLAIM, TENANT, "ASSUMED", "RNS-2026-0007731",
                UUID.fromString("22222222-2222-7222-8222-222222222222"), "CED-778",
                AssumedClaimStatus.OPEN, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-02"),
                new BigDecimal("12500.0000"), "USD", 3, now, now);
    }

    private static BordereauBatch batch(String hash) {
        Instant now = Instant.parse("2026-10-19T12:00:00Z");
        return new BordereauBatch(UUID.randomUUID(), TENANT, CLAIM, REFERENCE, "CED-778",
                LocalDate.parse("2026-09-03"), new BigDecimal("100.0000"), "USD",
                BordereauStatus.PARTIAL, AdviceSource.BORDEREAU, 2, 2, 1, 1, 0, hash, now, now, now);
    }

    private static LossAdvice advice(String number) {
        Instant now = Instant.parse("2026-10-19T12:00:00Z");
        return new LossAdvice(UUID.randomUUID(), TENANT, CLAIM, number, "CED-778", AdviceType.UPDATE,
                LocalDate.parse("2026-09-03"), LocalDate.parse("2026-09-03"), null, null,
                new BigDecimal("100.0000"), null, null, null, null, null, "USD", null, null, false,
                AdviceStatus.RECEIVED, null, null, null, 1, AdviceSource.BORDEREAU, 1, null, null, now, now);
    }
}
