package com.eia.assumedclaims.expert;

import com.eia.platform.libs.outbox.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
class CashCallServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID CLAIM = UUID.fromString("0192f3a4-5b1c-7e2d-9a3f-6c8d1e0b4a72");
    private static final UUID APPROVER = UUID.fromString("0192f3a4-bbbb-7bbb-8bbb-000000000002");

    @Mock
    private AssumedClaimService claimService;
    @Mock
    private CashCallRepository cashCalls;
    @Mock
    private ReserveRepository reserves;
    @Mock
    private AssumedClaimRepository claims;
    @Mock
    private ClaimAuditRepository audit;
    @Mock
    private NumberSequences numbers;
    @Mock
    private OutboxRepository outbox;

    private CashCallService service;

    @BeforeEach
    void setUp() {
        service = new CashCallService(claimService, cashCalls, reserves, claims, audit, numbers, outbox);
    }

    @Test
    void cashCallCannotExceedReserve() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.OPEN));
        given(reserves.currentTotal(TENANT, CLAIM)).willReturn(new BigDecimal("100000.00"));
        given(cashCalls.outstandingTotal(TENANT, CLAIM)).willReturn(BigDecimal.ZERO);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.create(TENANT, CLAIM, request("200000.00", null, null), "adjuster"));

        assertEquals("EIA-RNS-7301", exception.code());
        assertEquals(422, exception.status().value());
        verify(cashCalls, never()).insert(any());
    }

    @Test
    void outstandingCallsReduceAvailableReserve() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.RESERVED));
        given(reserves.currentTotal(TENANT, CLAIM)).willReturn(new BigDecimal("100000.00"));
        given(cashCalls.outstandingTotal(TENANT, CLAIM)).willReturn(new BigDecimal("80000.00"));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.create(TENANT, CLAIM, request("30000.00", null, null), "adjuster"));

        assertEquals("EIA-RNS-7301", exception.code());
    }

    @Test
    void largeCallRequiresApproval() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.OPEN));
        given(reserves.currentTotal(TENANT, CLAIM)).willReturn(new BigDecimal("500000.00"));
        given(cashCalls.outstandingTotal(TENANT, CLAIM)).willReturn(BigDecimal.ZERO);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.create(TENANT, CLAIM, request("300000.00", null, null), "adjuster"));

        assertEquals("EIA-RNS-7302", exception.code());
        assertEquals(428, exception.status().value());
    }

    @Test
    void smallCallIsSubmittedWithoutApproval() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.OPEN));
        given(reserves.currentTotal(TENANT, CLAIM)).willReturn(new BigDecimal("200000.00"));
        given(cashCalls.outstandingTotal(TENANT, CLAIM)).willReturn(BigDecimal.ZERO);
        given(numbers.nextCashCallNumber()).willReturn("CSH-2026-000001");

        CashCall call = service.create(TENANT, CLAIM, request("100000.00", null, null), "adjuster");

        assertEquals(CashCallStatus.SUBMITTED, call.status());
        assertEquals("NOT_REQUIRED", call.approvalStatus());
        verify(cashCalls).insert(call);
        verify(claims).touch(TENANT, CLAIM);
        verify(audit).append(eq(TENANT), eq(CLAIM), eq(AuditEventType.CASH_CALL_REQUESTED), eq("adjuster"), any());
    }

    @Test
    void approvedLargeCallIsRecordedAsApproved() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.RESERVED));
        given(reserves.currentTotal(TENANT, CLAIM)).willReturn(new BigDecimal("500000.00"));
        given(cashCalls.outstandingTotal(TENANT, CLAIM)).willReturn(BigDecimal.ZERO);
        given(numbers.nextCashCallNumber()).willReturn("CSH-2026-000002");

        CashCall call = service.create(TENANT, CLAIM, request("300000.00", APPROVER, ApproverRole.CLAIMS), "adjuster");

        assertEquals(CashCallStatus.APPROVED, call.status());
        assertEquals(APPROVER, call.approvedBy());
        assertEquals(ApproverRole.CLAIMS, call.approverRole());
        ArgumentCaptor<CashCall> captor = ArgumentCaptor.forClass(CashCall.class);
        verify(cashCalls).insert(captor.capture());
        assertEquals("APPROVED", captor.getValue().approvalStatus());
    }

    @Test
    void wrongApproverRoleIsRejected() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.OPEN));
        given(reserves.currentTotal(TENANT, CLAIM)).willReturn(new BigDecimal("500000.00"));
        given(cashCalls.outstandingTotal(TENANT, CLAIM)).willReturn(BigDecimal.ZERO);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.create(TENANT, CLAIM,
                        request("300000.00", APPROVER, ApproverRole.ACTUARIAL), "adjuster"));

        assertEquals("EIA-RNS-7302", exception.code());
    }

    @Test
    void currencyMustMatchClaim() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.OPEN));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.create(TENANT, CLAIM,
                        new RequestCashCallRequest(new BigDecimal("1000.00"), "EUR", CashCallReason.OTHER,
                                null, null, null, null, null, null), "adjuster"));

        assertEquals("EIA-RNS-7703", exception.code());
    }

    @Test
    void notifiedClaimCannotRequestCash() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim(AssumedClaimStatus.NOTIFIED));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.create(TENANT, CLAIM, request("1000.00", null, null), "adjuster"));

        assertEquals("EIA-RNS-7205", exception.code());
    }

    private static RequestCashCallRequest request(String amount, UUID approvedBy, ApproverRole role) {
        return new RequestCashCallRequest(new BigDecimal(amount), "USD", CashCallReason.LARGE_LOSS,
                "Interim funding", LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-15"),
                approvedBy, role, null);
    }

    private static AssumedClaim claim(AssumedClaimStatus status) {
        Instant now = Instant.parse("2026-10-19T12:00:00Z");
        return new AssumedClaim(CLAIM, TENANT, "ASSUMED", "RNS-2026-0007731",
                UUID.fromString("22222222-2222-7222-8222-222222222222"), "CED-778", status,
                LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-02"),
                new BigDecimal("12500.0000"), "USD", 3, now, now);
    }
}
