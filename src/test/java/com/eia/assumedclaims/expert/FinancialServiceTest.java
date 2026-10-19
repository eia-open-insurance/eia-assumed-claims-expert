package com.eia.assumedclaims.expert;

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
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class FinancialServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID CLAIM = UUID.fromString("0192f3a4-5b1c-7e2d-9a3f-6c8d1e0b4a72");

    @Mock
    private AssumedClaimService claimService;
    @Mock
    private LossAdviceRepository advices;
    @Mock
    private ReserveRepository reserves;
    @Mock
    private RecoveryRepository recoveries;
    @Mock
    private CashCallRepository cashCalls;

    private FinancialService service;

    @BeforeEach
    void setUp() {
        service = new FinancialService(claimService, advices, reserves, recoveries, cashCalls);
    }

    @Test
    void summaryDerivesCededIncurredAndNetAmounts() {
        given(claimService.requireClaim(TENANT, CLAIM)).willReturn(claim());
        given(advices.cededPaid(TENANT, CLAIM)).willReturn(new BigDecimal("100000.00"));
        given(advices.cededOutstanding(TENANT, CLAIM)).willReturn(new BigDecimal("50000.00"));
        given(reserves.currentTotal(TENANT, CLAIM)).willReturn(new BigDecimal("250000.00"));
        given(reserves.currentComponentTotal(TENANT, CLAIM, ReserveComponent.IBNR))
                .willReturn(new BigDecimal("40000.00"));
        given(recoveries.recognizedTotal(TENANT, CLAIM)).willReturn(new BigDecimal("25000.00"));
        given(cashCalls.paidTotal(TENANT, CLAIM)).willReturn(new BigDecimal("75000.00"));
        given(advices.advisedLoss(TENANT, CLAIM)).willReturn(new BigDecimal("300000.00"));
        given(advices.lastAdviceAt(TENANT, CLAIM)).willReturn(Instant.parse("2026-10-19T12:00:00Z"));

        FinancialSummary summary = service.summary(TENANT, CLAIM);

        assertEquals("USD", summary.currency());
        assertEquals(new BigDecimal("100000.0000"), summary.cededPaidAmount());
        assertEquals(new BigDecimal("400000.0000"), summary.cededIncurredAmount());
        assertEquals(new BigDecimal("375000.0000"), summary.netCededAmount());
        assertEquals(new BigDecimal("40000.0000"), summary.cededIbnrAmount());
        assertEquals(new BigDecimal("75000.0000"), summary.cashCallAmount());
        assertEquals(6, summary.sourceVersion());
    }

    private static AssumedClaim claim() {
        Instant now = Instant.parse("2026-10-19T12:00:00Z");
        return new AssumedClaim(CLAIM, TENANT, "ASSUMED", "RNS-2026-0007731",
                UUID.fromString("22222222-2222-7222-8222-222222222222"), "CED-778",
                AssumedClaimStatus.RESERVED, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-02"),
                new BigDecimal("250000.0000"), "USD", 6, now, now);
    }
}
