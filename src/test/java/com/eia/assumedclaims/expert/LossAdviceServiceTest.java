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
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LossAdviceServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID CLAIM = UUID.fromString("0192f3a4-5b1c-7e2d-9a3f-6c8d1e0b4a72");
    private static final UUID ADVICE = UUID.fromString("0192f3a4-aaaa-7aaa-8aaa-000000000001");

    @Mock
    private AssumedClaimService claimService;
    @Mock
    private LossAdviceRepository advices;
    @Mock
    private AssumedClaimRepository claims;
    @Mock
    private ClaimAuditRepository audit;

    private LossAdviceService service;

    @BeforeEach
    void setUp() {
        service = new LossAdviceService(claimService, advices, claims, audit);
    }

    @Test
    void recordsAnAdviceAsReceivedAndTouchesTheClaim() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.OPEN));
        given(advices.existsByAdviceNumber(TENANT, CLAIM, "ADV-001")).willReturn(false);

        LossAdvice advice = service.record(TENANT, CLAIM, request("ADV-001", "USD", "2026-09-03"), "adjuster");

        assertEquals(AdviceStatus.RECEIVED, advice.status());
        assertEquals("CED-778", advice.cedentClaimNumber());
        verify(advices).insert(advice);
        verify(claims).touch(TENANT, CLAIM);
        verify(audit).append(eq(TENANT), eq(CLAIM), eq(AuditEventType.LOSS_ADVICE_RECORDED), eq("adjuster"), any());
    }

    @Test
    void duplicateAdviceNumberIsRejected() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.OPEN));
        given(advices.existsByAdviceNumber(TENANT, CLAIM, "ADV-001")).willReturn(true);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.record(TENANT, CLAIM, request("ADV-001", "USD", "2026-09-03"), "adjuster"));

        assertEquals("EIA-RNS-7101", exception.code());
        assertEquals(409, exception.status().value());
        verify(advices, never()).insert(any());
    }

    @Test
    void currencyMustMatchTheClaim() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.OPEN));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.record(TENANT, CLAIM, request("ADV-001", "EUR", "2026-09-03"), "adjuster"));

        assertEquals("EIA-RNS-7703", exception.code());
        assertEquals(400, exception.status().value());
    }

    @Test
    void adviceDateCannotPrecedeLossDate() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.OPEN));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.record(TENANT, CLAIM, request("ADV-001", "USD", "2026-08-01"), "adjuster"));

        assertEquals("EIA-RNS-7004", exception.code());
        assertEquals("adviceDate", exception.errors().getFirst().field());
    }

    @Test
    void receivedDateCannotPrecedeAdviceDate() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.OPEN));
        RecordAdviceRequest request = new RecordAdviceRequest(
                "ADV-001", AdviceType.INITIAL_NOTICE, LocalDate.parse("2026-09-03"),
                LocalDate.parse("2026-09-02"), null, null, new BigDecimal("1000.00"), null, null,
                null, null, null, "USD", null, null, null, null, null, null);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.record(TENANT, CLAIM, request, "adjuster"));

        assertEquals("EIA-RNS-7004", exception.code());
        assertEquals("receivedDate", exception.errors().getFirst().field());
    }

    @Test
    void adviceCannotBeRecordedOnAClosedClaim() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.CLOSED));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.record(TENANT, CLAIM, request("ADV-001", "USD", "2026-09-03"), "adjuster"));

        assertEquals("EIA-RNS-7205", exception.code());
    }

    @Test
    void acceptingAReceivedAdvicePostsIt() {
        LossAdvice received = advice("ADV-001", AdviceStatus.RECEIVED);
        LossAdvice posted = advice("ADV-001", AdviceStatus.POSTED);
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.OPEN));
        given(advices.findById(TENANT, CLAIM, ADVICE)).willReturn(Optional.of(received));
        given(advices.accept(eq(TENANT), eq(CLAIM), eq(ADVICE), any())).willReturn(posted);

        LossAdvice result = service.accept(TENANT, CLAIM, ADVICE, null, "adjuster");

        assertEquals(AdviceStatus.POSTED, result.status());
        verify(claims).touch(TENANT, CLAIM);
        verify(audit).append(eq(TENANT), eq(CLAIM), eq(AuditEventType.LOSS_ADVICE_ACCEPTED), eq("adjuster"), any());
    }

    @Test
    void acceptingAnAlreadyPostedAdviceIsIdempotent() {
        LossAdvice posted = advice("ADV-001", AdviceStatus.POSTED);
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.OPEN));
        given(advices.findById(TENANT, CLAIM, ADVICE)).willReturn(Optional.of(posted));

        LossAdvice result = service.accept(TENANT, CLAIM, ADVICE, null, "adjuster");

        assertSame(posted, result);
        verify(advices, never()).accept(any(), any(), any(), any());
    }

    @Test
    void rejectedAdviceCannotBeAccepted() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.OPEN));
        given(advices.findById(TENANT, CLAIM, ADVICE)).willReturn(Optional.of(advice("ADV-001", AdviceStatus.REJECTED)));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.accept(TENANT, CLAIM, ADVICE, null, "adjuster"));

        assertEquals("EIA-RNS-7103", exception.code());
        assertEquals(409, exception.status().value());
    }

    @Test
    void missingAdviceIs404() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.OPEN));
        given(advices.findById(TENANT, CLAIM, ADVICE)).willReturn(Optional.empty());

        ApiException exception = assertThrows(ApiException.class,
                () -> service.accept(TENANT, CLAIM, ADVICE, null, "adjuster"));

        assertEquals("EIA-RNS-7007", exception.code());
        assertEquals(404, exception.status().value());
    }

    @Test
    void cedentClaimNumberSnapshotUsesTheClaimWhenRequestOmitsIt() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.OPEN));
        given(advices.existsByAdviceNumber(TENANT, CLAIM, "ADV-001")).willReturn(false);
        RecordAdviceRequest withoutCedent = new RecordAdviceRequest(
                "ADV-001", AdviceType.INITIAL_NOTICE, LocalDate.parse("2026-09-03"), null, null, null,
                new BigDecimal("1000.00"), null, null, null, null, null, "USD", null, null, null, null, null, null);

        LossAdvice advice = service.record(TENANT, CLAIM, withoutCedent, "adjuster");

        ArgumentCaptor<LossAdvice> captor = ArgumentCaptor.forClass(LossAdvice.class);
        verify(advices).insert(captor.capture());
        assertEquals("CED-778", captor.getValue().cedentClaimNumber());
        assertEquals("ADV-001", advice.adviceNumber());
    }

    private static RecordAdviceRequest request(String number, String currency, String adviceDate) {
        return new RecordAdviceRequest(
                number,
                AdviceType.INITIAL_NOTICE,
                LocalDate.parse(adviceDate),
                LocalDate.parse(adviceDate),
                null,
                null,
                new BigDecimal("250000.00"),
                null,
                null,
                null,
                null,
                null,
                currency,
                new BigDecimal("0.500000"),
                null,
                null,
                null,
                null,
                null);
    }

    private static LossAdvice advice(String number, AdviceStatus status) {
        Instant now = Instant.parse("2026-10-19T12:00:00Z");
        return new LossAdvice(ADVICE, TENANT, CLAIM, number, "CED-778", AdviceType.INITIAL_NOTICE,
                LocalDate.parse("2026-09-03"), LocalDate.parse("2026-09-03"), null, null,
                new BigDecimal("250000.0000"), null, null, null, null, null, "USD",
                new BigDecimal("0.500000"), null, false, status, null, null, null, null,
                AdviceSource.MANUAL, 1, status == AdviceStatus.POSTED ? now : null, null, now, now);
    }

    private static AssumedClaim claim(AssumedClaimStatus status) {
        Instant now = Instant.parse("2026-10-19T12:00:00Z");
        return new AssumedClaim(CLAIM, TENANT, "ASSUMED", "RNS-2026-0007731",
                UUID.fromString("22222222-2222-7222-8222-222222222222"), "CED-778", status,
                LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-02"),
                new BigDecimal("12500.0000"), "USD", 2, now, now);
    }
}
