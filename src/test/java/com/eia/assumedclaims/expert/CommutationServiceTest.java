package com.eia.assumedclaims.expert;

import com.eia.platform.libs.outbox.OutboxRepository;
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
class CommutationServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID CLAIM = UUID.fromString("0192f3a4-5b1c-7e2d-9a3f-6c8d1e0b4a72");
    private static final UUID APPROVER = UUID.fromString("0192f3a4-bbbb-7bbb-8bbb-000000000002");
    private static final UUID DOCUMENT = UUID.fromString("0192f3a4-cccc-7ccc-8ccc-000000000003");

    @Mock
    private AssumedClaimService claimService;
    @Mock
    private CommutationRepository commutations;
    @Mock
    private ReserveRepository reserves;
    @Mock
    private DisputeRepository disputes;
    @Mock
    private LossAdviceRepository advices;
    @Mock
    private AssumedClaimRepository claims;
    @Mock
    private ClaimAuditRepository audit;
    @Mock
    private NumberSequences numbers;
    @Mock
    private OutboxRepository outbox;

    private CommutationService service;

    @BeforeEach
    void setUp() {
        service = new CommutationService(claimService, commutations, reserves, disputes, advices,
                claims, audit, numbers, outbox);
    }

    @Test
    void commutationRequiresUnderwriterOrClaimsApproval() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.RESERVED));
        given(commutations.findByClaim(TENANT, CLAIM)).willReturn(Optional.empty());

        ApiException exception = assertThrows(ApiException.class,
                () -> service.execute(TENANT, CLAIM, request(ApproverRole.ACTUARIAL, DOCUMENT), "adjuster"));

        assertEquals("EIA-RNS-7501", exception.code());
        assertEquals(428, exception.status().value());
        verify(commutations, never()).insert(any());
    }

    @Test
    void commutationRequiresASignedReleaseDocument() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.RESERVED));
        given(commutations.findByClaim(TENANT, CLAIM)).willReturn(Optional.empty());

        ApiException exception = assertThrows(ApiException.class,
                () -> service.execute(TENANT, CLAIM, request(ApproverRole.UNDERWRITER, null), "adjuster"));

        assertEquals("EIA-RNS-7503", exception.code());
        assertEquals(400, exception.status().value());
    }

    @Test
    void openDisputeBlocksCommutation() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.RESERVED));
        given(commutations.findByClaim(TENANT, CLAIM)).willReturn(Optional.empty());
        given(disputes.existsOpen(TENANT, CLAIM)).willReturn(true);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.execute(TENANT, CLAIM, request(ApproverRole.CLAIMS, DOCUMENT), "adjuster"));

        assertEquals("EIA-RNS-7502", exception.code());
        assertEquals(422, exception.status().value());
    }

    @Test
    void unresolvedAdviceBlocksCommutation() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.RESERVED));
        given(commutations.findByClaim(TENANT, CLAIM)).willReturn(Optional.empty());
        given(disputes.existsOpen(TENANT, CLAIM)).willReturn(false);
        given(advices.countByStatus(TENANT, CLAIM, AdviceStatus.RECEIVED)).willReturn(1L);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.execute(TENANT, CLAIM, request(ApproverRole.CLAIMS, DOCUMENT), "adjuster"));

        assertEquals("EIA-RNS-7502", exception.code());
    }

    @Test
    void openClaimCanBeCommutedAndIsClosed() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.OPEN));
        given(commutations.findByClaim(TENANT, CLAIM)).willReturn(Optional.empty());
        given(disputes.existsOpen(TENANT, CLAIM)).willReturn(false);
        given(advices.countByStatus(TENANT, CLAIM, AdviceStatus.RECEIVED)).willReturn(0L);
        given(numbers.nextCommutationNumber()).willReturn("COM-2026-000001");
        given(claims.closeAfterCommutation(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.CLOSED));

        Commutation commutation = service.execute(
                TENANT, CLAIM, request(ApproverRole.UNDERWRITER, DOCUMENT), "adjuster");

        assertEquals(CommutationStatus.EXECUTED, commutation.status());
        assertEquals(0, new BigDecimal("750000.00").compareTo(commutation.acceptedAmount()));
        assertEquals(0, new BigDecimal("50000.00").compareTo(commutation.savingsAmount()));
        verify(reserves).deactivateAll(TENANT, CLAIM);
        verify(claims).closeAfterCommutation(TENANT, CLAIM);
        verify(audit).append(eq(TENANT), eq(CLAIM), eq(AuditEventType.COMMUTATION_EXECUTED), eq("adjuster"), any());
    }

    @Test
    void claimNotInACommutableStatusIsRejected() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.PAID));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.execute(TENANT, CLAIM, request(ApproverRole.UNDERWRITER, DOCUMENT), "adjuster"));

        assertEquals("EIA-RNS-7502", exception.code());
    }

    @Test
    void secondCommutationIsRejected() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.RESERVED));
        given(commutations.findByClaim(TENANT, CLAIM)).willReturn(Optional.of(commutation()));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.execute(TENANT, CLAIM, request(ApproverRole.UNDERWRITER, DOCUMENT), "adjuster"));

        assertEquals("EIA-RNS-7502", exception.code());
    }

    private static CommuteClaimRequest request(ApproverRole role, UUID document) {
        return new CommuteClaimRequest(new BigDecimal("800000.00"), new BigDecimal("750000.00"), "USD",
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-01"), CommutationType.FULL,
                CommutationBasis.NEGOTIATED, APPROVER, role, document, null);
    }

    private static Commutation commutation() {
        Instant now = Instant.parse("2026-10-19T12:00:00Z");
        return new Commutation(UUID.randomUUID(), TENANT, CLAIM, "COM-2026-000000", CommutationType.FULL,
                new BigDecimal("800000.00"), new BigDecimal("750000.00"), "USD",
                LocalDate.parse("2026-10-01"), null, CommutationBasis.NEGOTIATED, DOCUMENT,
                CommutationStatus.EXECUTED, APPROVER, ApproverRole.UNDERWRITER, now, now,
                new BigDecimal("50000.00"), 1, now, now);
    }

    private static AssumedClaim claim(AssumedClaimStatus status) {
        Instant now = Instant.parse("2026-10-19T12:00:00Z");
        return new AssumedClaim(CLAIM, TENANT, "ASSUMED", "RNS-2026-0007731",
                UUID.fromString("22222222-2222-7222-8222-222222222222"), "CED-778", status,
                LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-02"),
                new BigDecimal("12500.0000"), "USD", 3, now, now);
    }
}
