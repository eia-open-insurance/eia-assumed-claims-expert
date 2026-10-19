package com.eia.assumedclaims.expert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DisputeServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID CLAIM = UUID.fromString("0192f3a4-5b1c-7e2d-9a3f-6c8d1e0b4a72");
    private static final UUID DISPUTE = UUID.fromString("0192f3a4-dddd-7ddd-8ddd-000000000004");

    @Mock
    private AssumedClaimService claimService;
    @Mock
    private DisputeRepository disputes;
    @Mock
    private AssumedClaimRepository claims;
    @Mock
    private ClaimAuditRepository audit;
    @Mock
    private NumberSequences numbers;

    private DisputeService service;

    @BeforeEach
    void setUp() {
        service = new DisputeService(claimService, disputes, claims, audit, numbers);
    }

    @Test
    void raiseStoresAnOpenDispute() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.IN_ADJUSTMENT));
        given(numbers.nextDisputeNumber()).willReturn("DSP-2026-000001");

        Dispute dispute = service.raise(TENANT, CLAIM, raiseRequest("USD"), "adjuster");

        assertEquals(DisputeStatus.OPEN, dispute.status());
        assertEquals(0, new BigDecimal("100000.00").compareTo(dispute.contestedAmount()));
        verify(disputes).insert(dispute);
        verify(claims).touch(TENANT, CLAIM);
        verify(audit).append(eq(TENANT), eq(CLAIM), eq(AuditEventType.DISPUTE_RAISED), eq("adjuster"), any());
    }

    @Test
    void disputeCannotBeRaisedOnANotifiedClaim() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.NOTIFIED));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.raise(TENANT, CLAIM, raiseRequest("USD"), "adjuster"));

        assertEquals("EIA-RNS-7603", exception.code());
    }

    @Test
    void disputeCurrencyMustMatchTheClaim() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.OPEN));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.raise(TENANT, CLAIM, raiseRequest("EUR"), "adjuster"));

        assertEquals("EIA-RNS-7703", exception.code());
    }

    @Test
    void resolveRequiresAWrittenResolution() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.IN_ADJUSTMENT));
        given(disputes.findById(TENANT, CLAIM, DISPUTE)).willReturn(Optional.of(dispute(DisputeStatus.OPEN)));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.resolve(TENANT, CLAIM, DISPUTE,
                        new ResolveDisputeRequest(" ", DisputeStatus.RESOLVED, null), "adjuster"));

        assertEquals("EIA-RNS-7004", exception.code());
        assertEquals("resolution", exception.errors().getFirst().field());
    }

    @Test
    void alreadyResolvedDisputeCannotBeResolvedAgain() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.IN_ADJUSTMENT));
        given(disputes.findById(TENANT, CLAIM, DISPUTE)).willReturn(Optional.of(dispute(DisputeStatus.RESOLVED)));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.resolve(TENANT, CLAIM, DISPUTE,
                        new ResolveDisputeRequest("Settled", DisputeStatus.RESOLVED, null), "adjuster"));

        assertEquals("EIA-RNS-7602", exception.code());
        verify(disputes, never()).resolve(any(), any(), any(), any(), any(), any());
    }

    @Test
    void resolutionDateCannotPrecedeTheRaiseDate() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.IN_ADJUSTMENT));
        given(disputes.findById(TENANT, CLAIM, DISPUTE)).willReturn(Optional.of(dispute(DisputeStatus.OPEN)));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.resolve(TENANT, CLAIM, DISPUTE,
                        new ResolveDisputeRequest("Settled", DisputeStatus.RESOLVED, LocalDate.parse("2026-09-30")),
                        "adjuster"));

        assertEquals("EIA-RNS-7004", exception.code());
        assertEquals("resolvedAt", exception.errors().getFirst().field());
    }

    @Test
    void resolveUpdatesStatusAndAudit() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.IN_ADJUSTMENT));
        given(disputes.findById(TENANT, CLAIM, DISPUTE)).willReturn(Optional.of(dispute(DisputeStatus.OPEN)));
        given(disputes.resolve(TENANT, CLAIM, DISPUTE, DisputeStatus.RESOLVED, "Settled",
                LocalDate.parse("2026-10-10"))).willReturn(dispute(DisputeStatus.RESOLVED));

        Dispute resolved = service.resolve(TENANT, CLAIM, DISPUTE,
                new ResolveDisputeRequest("Settled", DisputeStatus.RESOLVED, LocalDate.parse("2026-10-10")),
                "adjuster");

        assertEquals(DisputeStatus.RESOLVED, resolved.status());
        verify(claims).touch(TENANT, CLAIM);
        verify(audit).append(eq(TENANT), eq(CLAIM), eq(AuditEventType.DISPUTE_RESOLVED), eq("adjuster"), any());
    }

    private static RaiseDisputeRequest raiseRequest(String currency) {
        return new RaiseDisputeRequest(DisputeType.QUANTUM, "Quantum is disputed", new BigDecimal("100000.00"),
                currency, LocalDate.parse("2026-10-01"), false, null);
    }

    private static Dispute dispute(DisputeStatus status) {
        Instant now = Instant.parse("2026-10-19T12:00:00Z");
        return new Dispute(DISPUTE, TENANT, CLAIM, "DSP-2026-000001", DisputeType.QUANTUM,
                "Quantum is disputed", new BigDecimal("100000.00"), "USD", status,
                status == DisputeStatus.RESOLVED ? "Settled" : null,
                LocalDate.parse("2026-10-01"),
                status == DisputeStatus.RESOLVED ? LocalDate.parse("2026-10-10") : null,
                false, null, 1, now, now);
    }

    private static AssumedClaim claim(AssumedClaimStatus status) {
        Instant now = Instant.parse("2026-10-19T12:00:00Z");
        return new AssumedClaim(CLAIM, TENANT, "ASSUMED", "RNS-2026-0007731",
                UUID.fromString("22222222-2222-7222-8222-222222222222"), "CED-778", status,
                LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-02"),
                new BigDecimal("12500.0000"), "USD", 3, now, now);
    }
}
