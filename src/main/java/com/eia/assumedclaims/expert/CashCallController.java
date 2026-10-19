package com.eia.assumedclaims.expert;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/assumed-claims/{claimId}/cash-calls")
public class CashCallController {
    private final CashCallService cashCalls;

    public CashCallController(CashCallService cashCalls) {
        this.cashCalls = cashCalls;
    }

    @GetMapping
    public List<CashCall> list(
            @PathVariable UUID claimId,
            @RequestHeader("X-EIA-Tenant") UUID tenantId) {
        return cashCalls.list(tenantId, claimId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CashCall request(
            @PathVariable UUID claimId,
            @RequestHeader("X-EIA-Tenant") UUID tenantId,
            @RequestHeader(value = "X-EIA-User", required = false) String actor,
            @Valid @RequestBody RequestCashCallRequest request) {
        return cashCalls.create(tenantId, claimId, request, AssumedClaimService.actor(actor));
    }
}
