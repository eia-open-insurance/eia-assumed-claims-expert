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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ReserveServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID CLAIM = UUID.fromString("0192f3a4-5b1c-7e2d-9a3f-6c8d1e0b4a72");
    private static final UUID APPROVER = UUID.fromString("0192f3a4-bbbb-7bbb-8bbb-000000000002");

    @Mock
    private AssumedClaimService claimService;
    @Mock
    private ReserveRepository reserves;
    @Mock
    private AssumedClaimRepository claims;
    @Mock
    private ClaimAuditRepository audit;

    private ReserveService service;

    @BeforeEach
    void setUp() {
        service = new ReserveService(claimService, reserves, claims, audit);
    }

    @Test
    void negativeAmountIsRejected() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.OPEN));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.change(TENANT, CLAIM, request(ReserveComponent.CASE, "-0.01",
                        LocalDate.parse("2026-10-01"), ReserveReason.REVIEW, ReserveAuthority.ADJUSTER, null), null, "adjuster"));

        assertEquals("EIA-RNS-7201", exception.code());
        assertEquals(400, exception.status().value());
    }

    @Test
    void missingReasonIsRejected() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.OPEN));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.change(TENANT, CLAIM, request(ReserveComponent.CASE, "100.00",
                        LocalDate.parse("2026-10-01"), null, ReserveAuthority.ADJUSTER, null), null, "adjuster"));

        assertEquals("EIA-RNS-7201", exception.code());
        assertEquals("reasonCode", exception.errors().getFirst().field());
    }

    @Test
    void staleEffectiveDateIsRejected() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.OPEN));
        given(reserves.findLatest(TENANT, CLAIM, ReserveComponent.CASE))
                .willReturn(Optional.of(entry(2, "50000.00", LocalDate.parse("2026-10-05"))));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.change(TENANT, CLAIM, request(ReserveComponent.CASE, "100.00",
                        LocalDate.parse("2026-10-05"), ReserveReason.REVIEW, ReserveAuthority.ADJUSTER, null), null, "adjuster"));

        assertEquals("EIA-RNS-7202", exception.code());
        assertEquals(409, exception.status().value());
        verify(reserves, never()).insert(any());
    }

    @Test
    void ibnrRequiresActuarialOrUnderwritingAuthority() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.OPEN));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.change(TENANT, CLAIM, request(ReserveComponent.IBNR, "10000.00",
                        LocalDate.parse("2026-10-01"), ReserveReason.REVIEW, ReserveAuthority.ADJUSTER, null), null, "adjuster"));

        assertEquals("EIA-RNS-7203", exception.code());
        assertEquals(428, exception.status().value());
    }

    @Test
    void highReserveRequiresNamedApprover() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.OPEN));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.change(TENANT, CLAIM, request(ReserveComponent.CASE, "1000000.00",
                        LocalDate.parse("2026-10-01"), ReserveReason.REVIEW, ReserveAuthority.SENIOR_ADJUSTER, null), null, "adjuster"));

        assertEquals("EIA-RNS-7203", exception.code());
        assertEquals(428, exception.status().value());
    }

    @Test
    void approvedHighReserveIsAppendedAndBecomesCurrent() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.OPEN));
        given(reserves.findLatest(TENANT, CLAIM, ReserveComponent.CASE)).willReturn(Optional.empty());
        given(reserves.nextSequence(TENANT, CLAIM, ReserveComponent.CASE)).willReturn(1);
        given(reserves.currentTotal(TENANT, CLAIM)).willReturn(new BigDecimal("1000000.0000"));

        ReserveHistoryEntry result = service.change(TENANT, CLAIM,
                request(ReserveComponent.CASE, "1000000.00", LocalDate.parse("2026-10-01"),
                        ReserveReason.REVIEW, ReserveAuthority.SENIOR_ADJUSTER, APPROVER),
                UUID.fromString("0192f3a4-eeee-7eee-8eee-000000000005"), "adjuster");

        assertTrue(result.authorityRequired());
        assertEquals(0, new BigDecimal("1000000.00").compareTo(result.amount()));
        verify(reserves).supersedeCurrent(TENANT, CLAIM, ReserveComponent.CASE);
        ArgumentCaptor<ReserveHistoryEntry> captor = ArgumentCaptor.forClass(ReserveHistoryEntry.class);
        verify(reserves).insert(captor.capture());
        assertEquals(APPROVER, captor.getValue().approvedBy());
        verify(claims).updateReserveAmount(TENANT, CLAIM, new BigDecimal("1000000.0000"));
        verify(audit).append(eq(TENANT), eq(CLAIM), eq(AuditEventType.RESERVE_CHANGED), eq("adjuster"), any());
    }

    @Test
    void ordinaryReserveIsAppended() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.RESERVED));
        given(reserves.findLatest(TENANT, CLAIM, ReserveComponent.CASE)).willReturn(Optional.empty());
        given(reserves.nextSequence(TENANT, CLAIM, ReserveComponent.CASE)).willReturn(2);
        given(reserves.currentTotal(TENANT, CLAIM)).willReturn(new BigDecimal("150000.0000"));

        ReserveHistoryEntry result = service.change(TENANT, CLAIM,
                request(ReserveComponent.CASE, "150000.00", LocalDate.parse("2026-10-02"),
                        ReserveReason.DEVELOPMENT, ReserveAuthority.ADJUSTER, null), null, "adjuster");

        assertFalse(result.authorityRequired());
        assertEquals(2, result.sequence());
        assertEquals("USD", result.currency());
    }

    @Test
    void currencyMustMatchTheClaim() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.OPEN));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.change(TENANT, CLAIM, new ReserveChangeRequest(ReserveComponent.CASE,
                        new BigDecimal("100.00"), "EUR", LocalDate.parse("2026-10-01"),
                        ReserveReason.REVIEW, null, ReserveAuthority.ADJUSTER, null), null, "adjuster"));

        assertEquals("EIA-RNS-7703", exception.code());
    }

    @Test
    void notifiedClaimCannotTakeFinancialMovements() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.NOTIFIED));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.change(TENANT, CLAIM, request(ReserveComponent.CASE, "100.00",
                        LocalDate.parse("2026-10-01"), ReserveReason.REVIEW, ReserveAuthority.ADJUSTER, null), null, "adjuster"));

        assertEquals("EIA-RNS-7205", exception.code());
    }

    private static ReserveChangeRequest request(
            ReserveComponent component,
            String amount,
            LocalDate effectiveDate,
            ReserveReason reason,
            ReserveAuthority authority,
            UUID approvedBy
    ) {
        return new ReserveChangeRequest(component, new BigDecimal(amount), "USD", effectiveDate,
                reason, "test movement", authority, approvedBy);
    }

    private static ReserveHistoryEntry entry(int sequence, String amount, LocalDate effectiveDate) {
        return new ReserveHistoryEntry(UUID.randomUUID(), TENANT, CLAIM, ReserveComponent.CASE, sequence,
                new BigDecimal(amount), "USD", effectiveDate, ReserveReason.REVIEW, null,
                ReserveAuthority.ADJUSTER, false, null, null, true, 1,
                UUID.fromString("00000000-0000-0000-0000-000000000000"),
                Instant.parse("2026-10-19T12:00:00Z"));
    }

    private static AssumedClaim claim(AssumedClaimStatus status) {
        Instant now = Instant.parse("2026-10-19T12:00:00Z");
        return new AssumedClaim(CLAIM, TENANT, "ASSUMED", "RNS-2026-0007731",
                UUID.fromString("22222222-2222-7222-8222-222222222222"), "CED-778", status,
                LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-02"),
                new BigDecimal("12500.0000"), "USD", 3, now, now);
    }
}
