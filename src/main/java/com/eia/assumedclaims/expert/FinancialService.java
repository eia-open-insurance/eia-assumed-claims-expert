package com.eia.assumedclaims.expert;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/** Read-only ceded financial projection (SPEC §11.5.4/§11.5.12). */
@Service
public class FinancialService {
    private final AssumedClaimService claimService;
    private final LossAdviceRepository advices;
    private final ReserveRepository reserves;
    private final RecoveryRepository recoveries;
    private final CashCallRepository cashCalls;

    public FinancialService(
            AssumedClaimService claimService,
            LossAdviceRepository advices,
            ReserveRepository reserves,
            RecoveryRepository recoveries,
            CashCallRepository cashCalls) {
        this.claimService = claimService;
        this.advices = advices;
        this.reserves = reserves;
        this.recoveries = recoveries;
        this.cashCalls = cashCalls;
    }

    public FinancialSummary summary(UUID tenantId, UUID claimId) {
        AssumedClaim claim = claimService.requireClaim(tenantId, claimId);
        BigDecimal paid = scale(advices.cededPaid(tenantId, claimId));
        BigDecimal outstanding = scale(advices.cededOutstanding(tenantId, claimId));
        BigDecimal reserve = scale(reserves.currentTotal(tenantId, claimId));
        BigDecimal ibnr = scale(reserves.currentComponentTotal(tenantId, claimId, ReserveComponent.IBNR));
        BigDecimal recovery = scale(recoveries.recognizedTotal(tenantId, claimId));
        BigDecimal cashCall = scale(cashCalls.paidTotal(tenantId, claimId));
        BigDecimal incurred = scale(paid.add(outstanding).add(reserve));
        BigDecimal net = scale(incurred.subtract(recovery));
        BigDecimal advisedLoss = scale(advices.advisedLoss(tenantId, claimId));
        return new FinancialSummary(
                claimId,
                tenantId,
                claim.reserveCurrency(),
                paid,
                outstanding,
                reserve,
                ibnr,
                incurred,
                recovery,
                net,
                cashCall,
                advisedLoss,
                advices.lastAdviceAt(tenantId, claimId),
                claim.version());
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }
}
