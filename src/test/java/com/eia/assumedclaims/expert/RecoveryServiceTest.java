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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RecoveryServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID CLAIM = UUID.fromString("0192f3a4-5b1c-7e2d-9a3f-6c8d1e0b4a72");

    @Mock
    private AssumedClaimService claimService;
    @Mock
    private RecoveryRepository recoveries;
    @Mock
    private LossAdviceRepository advices;
    @Mock
    private AssumedClaimRepository claims;
    @Mock
    private ClaimAuditRepository audit;
    @Mock
    private OutboxRepository outbox;

    private RecoveryService service;

    @BeforeEach
    void setUp() {
        service = new RecoveryService(claimService, recoveries, advices, claims, audit, outbox);
    }

    @Test
    void recoveryCannotExceedAdvisedLoss() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.OPEN));
        given(advices.advisedLoss(TENANT, CLAIM)).willReturn(new BigDecimal("50000.0000"));
        given(recoveries.consumedCapacity(TENANT, CLAIM)).willReturn(BigDecimal.ZERO);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.create(TENANT, CLAIM, request("100000.00"), "adjuster"));

        assertEquals("EIA-RNS-7401", exception.code());
        assertEquals(422, exception.status().value());
        verify(recoveries, never()).insert(any());
    }

    @Test
    void cumulativeRecoveriesCannotExceedTheCap() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.OPEN));
        given(advices.advisedLoss(TENANT, CLAIM)).willReturn(new BigDecimal("100000.0000"));
        given(recoveries.consumedCapacity(TENANT, CLAIM)).willReturn(new BigDecimal("90000.0000"));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.create(TENANT, CLAIM, request("20000.00"), "adjuster"));

        assertEquals("EIA-RNS-7401", exception.code());
    }

    @Test
    void recoveryWithoutAcceptedAdviceIsRejected() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.OPEN));
        given(advices.advisedLoss(TENANT, CLAIM)).willReturn(BigDecimal.ZERO);
        given(recoveries.consumedCapacity(TENANT, CLAIM)).willReturn(BigDecimal.ZERO);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.create(TENANT, CLAIM, request("100.00"), "adjuster"));

        assertEquals("EIA-RNS-7401", exception.code());
    }

    @Test
    void recoveryWithinTheCapIsRecorded() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.IN_ADJUSTMENT));
        given(advices.advisedLoss(TENANT, CLAIM)).willReturn(new BigDecimal("125000.0000"));
        given(recoveries.consumedCapacity(TENANT, CLAIM)).willReturn(BigDecimal.ZERO);

        Recovery recovery = service.create(TENANT, CLAIM, request("25000.00"), "adjuster");

        assertEquals(RecoveryStatus.RECEIVED, recovery.status());
        assertEquals(0, new BigDecimal("25000.00").compareTo(recovery.amount()));
        verify(recoveries).insert(recovery);
        verify(claims).touch(TENANT, CLAIM);
        verify(audit).append(eq(TENANT), eq(CLAIM), eq(AuditEventType.RECOVERY_RECORDED), eq("adjuster"), any());
    }

    @Test
    void currencyMustMatchTheClaim() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.OPEN));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.create(TENANT, CLAIM, new RecordRecoveryRequest(RecoveryType.SALVAGE,
                        new BigDecimal("100.00"), "EUR", LocalDate.parse("2026-10-01"), null,
                        null, null, null, null), "adjuster"));

        assertEquals("EIA-RNS-7703", exception.code());
    }

    @Test
    void closedClaimCannotTakeRecovery() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.CLOSED));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.create(TENANT, CLAIM, request("100.00"), "adjuster"));

        assertEquals("EIA-RNS-7205", exception.code());
    }

    private static RecordRecoveryRequest request(String amount) {
        return new RecordRecoveryRequest(RecoveryType.SALVAGE, new BigDecimal(amount), "USD",
                LocalDate.parse("2026-10-01"), RecoveryStatus.RECEIVED, null, null, null, null);
    }

    private static AssumedClaim claim(AssumedClaimStatus status) {
        Instant now = Instant.parse("2026-10-19T12:00:00Z");
        return new AssumedClaim(CLAIM, TENANT, "ASSUMED", "RNS-2026-0007731",
                UUID.fromString("22222222-2222-7222-8222-222222222222"), "CED-778", status,
                LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-02"),
                new BigDecimal("12500.0000"), "USD", 3, now, now);
    }
}
