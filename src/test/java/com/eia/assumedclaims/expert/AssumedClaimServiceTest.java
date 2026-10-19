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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AssumedClaimServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID CLAIM = UUID.fromString("0192f3a4-5b1c-7e2d-9a3f-6c8d1e0b4a72");
    private static final UUID CONTRACT = UUID.fromString("22222222-2222-7222-8222-222222222222");

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

    private AssumedClaimService service;

    @BeforeEach
    void setUp() {
        service = new AssumedClaimService(claims, reserves, advices, cashCalls, disputes, audit);
    }

    @Test
    void createRegistersClaimAndInitialReserveHistory() {
        CreateAssumedClaimRequest request = new CreateAssumedClaimRequest(
                TENANT, CONTRACT, "CED-778", LocalDate.parse("2026-09-01"),
                LocalDate.parse("2026-09-02"), new BigDecimal("12500.00"), "USD");
        given(claims.create(request)).willReturn(claim(AssumedClaimStatus.NOTIFIED, 0));

        AssumedClaim created = service.create(request, "adjuster");

        assertEquals("RNS-2026-0007731", created.assumedClaimNumber());
        ArgumentCaptor<ReserveHistoryEntry> reserve = ArgumentCaptor.forClass(ReserveHistoryEntry.class);
        verify(reserves).insert(reserve.capture());
        assertEquals(ReserveComponent.CASE, reserve.getValue().component());
        assertEquals(ReserveReason.INITIAL, reserve.getValue().reasonCode());
        assertEquals(0, new BigDecimal("12500.00").compareTo(reserve.getValue().amount()));
        verify(audit).append(eq(TENANT), eq(CLAIM), eq(AuditEventType.CLAIM_REGISTERED), eq("adjuster"), any());
    }

    @Test
    void openRequiresAnAcceptedAdvice() {
        given(claims.findById(TENANT, CLAIM)).willReturn(Optional.of(claim(AssumedClaimStatus.NOTIFIED, 0)));
        given(advices.existsAccepted(TENANT, CLAIM)).willReturn(false);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.changeStatus(TENANT, CLAIM, AssumedClaimStatus.OPEN, 0, "adjuster"));

        assertEquals("EIA-RNS-7102", exception.code());
        assertEquals(422, exception.status().value());
        verify(claims, never()).changeStatus(any(), any(), any(), anyInt());
    }

    @Test
    void openSucceedsWithAnAcceptedAdvice() {
        given(claims.findById(TENANT, CLAIM)).willReturn(Optional.of(claim(AssumedClaimStatus.NOTIFIED, 0)));
        given(advices.existsAccepted(TENANT, CLAIM)).willReturn(true);
        given(claims.changeStatus(TENANT, CLAIM, AssumedClaimStatus.OPEN, 0))
                .willReturn(claim(AssumedClaimStatus.OPEN, 1));

        AssumedClaim updated = service.changeStatus(TENANT, CLAIM, AssumedClaimStatus.OPEN, 0, "adjuster");

        assertEquals(AssumedClaimStatus.OPEN, updated.status());
        verify(audit).append(eq(TENANT), eq(CLAIM), eq(AuditEventType.CLAIM_STATUS_CHANGED), eq("adjuster"), any());
    }

    @Test
    void reservedRequiresAPositiveCurrentReserve() {
        given(claims.findById(TENANT, CLAIM)).willReturn(Optional.of(claim(AssumedClaimStatus.OPEN, 1)));
        given(reserves.currentTotal(TENANT, CLAIM)).willReturn(BigDecimal.ZERO);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.changeStatus(TENANT, CLAIM, AssumedClaimStatus.RESERVED, 1, "adjuster"));

        assertEquals("EIA-RNS-7204", exception.code());
    }

    @Test
    void commutedStatusCannotBypassTheCommuteCommand() {
        given(claims.findById(TENANT, CLAIM)).willReturn(Optional.of(claim(AssumedClaimStatus.RESERVED, 3)));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.changeStatus(TENANT, CLAIM, AssumedClaimStatus.COMMUTED, 3, "adjuster"));

        assertEquals("EIA-RNS-7505", exception.code());
        verify(claims, never()).changeStatus(any(), any(), any(), anyInt());
    }

    @Test
    void closeIsBlockedByAnOpenDispute() {
        given(claims.findById(TENANT, CLAIM)).willReturn(Optional.of(claim(AssumedClaimStatus.PAID, 7)));
        given(disputes.existsOpen(TENANT, CLAIM)).willReturn(true);

        ApiException exception = assertThrows(ApiException.class, () -> service.close(TENANT, CLAIM, "adjuster"));

        assertEquals("EIA-RNS-7701", exception.code());
        assertEquals(422, exception.status().value());
    }

    @Test
    void closeIsBlockedByACurrentReserve() {
        given(claims.findById(TENANT, CLAIM)).willReturn(Optional.of(claim(AssumedClaimStatus.PAID, 7)));
        given(reserves.currentTotal(TENANT, CLAIM)).willReturn(new BigDecimal("10.00"));

        ApiException exception = assertThrows(ApiException.class, () -> service.close(TENANT, CLAIM, "adjuster"));

        assertEquals("EIA-RNS-7701", exception.code());
    }

    @Test
    void closeIsBlockedByUnresolvedAdvice() {
        given(claims.findById(TENANT, CLAIM)).willReturn(Optional.of(claim(AssumedClaimStatus.PAID, 7)));
        given(advices.countByStatus(TENANT, CLAIM, AdviceStatus.RECEIVED)).willReturn(1L);

        ApiException exception = assertThrows(ApiException.class, () -> service.close(TENANT, CLAIM, "adjuster"));

        assertEquals("EIA-RNS-7701", exception.code());
    }

    @Test
    void closeIsBlockedByOutstandingCashCall() {
        given(claims.findById(TENANT, CLAIM)).willReturn(Optional.of(claim(AssumedClaimStatus.PAID, 7)));
        given(cashCalls.outstandingTotal(TENANT, CLAIM)).willReturn(new BigDecimal("500.00"));

        ApiException exception = assertThrows(ApiException.class, () -> service.close(TENANT, CLAIM, "adjuster"));

        assertEquals("EIA-RNS-7701", exception.code());
    }

    @Test
    void closeSucceedsWhenAllClosureCriteriaAreMet() {
        given(claims.findById(TENANT, CLAIM)).willReturn(Optional.of(claim(AssumedClaimStatus.PAID, 7)));
        given(claims.changeStatus(TENANT, CLAIM, AssumedClaimStatus.CLOSED, 7))
                .willReturn(claim(AssumedClaimStatus.CLOSED, 8));

        AssumedClaim closed = service.close(TENANT, CLAIM, "adjuster");

        assertEquals(AssumedClaimStatus.CLOSED, closed.status());
        verify(audit).append(eq(TENANT), eq(CLAIM), eq(AuditEventType.CLAIM_CLOSED), eq("adjuster"), any());
    }

    @Test
    void staleExpectedVersionIsRejected() {
        given(claims.findById(TENANT, CLAIM)).willReturn(Optional.of(claim(AssumedClaimStatus.OPEN, 2)));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.changeStatus(TENANT, CLAIM, AssumedClaimStatus.RESERVED, 1, "adjuster"));

        assertEquals("EIA-RNS-7006", exception.code());
    }

    private static AssumedClaim claim(AssumedClaimStatus status, int version) {
        Instant now = Instant.parse("2026-10-19T12:00:00Z");
        return new AssumedClaim(CLAIM, TENANT, "ASSUMED", "RNS-2026-0007731", CONTRACT, "CED-778",
                status, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-02"),
                new BigDecimal("12500.0000"), "USD", version, now, now);
    }
}
