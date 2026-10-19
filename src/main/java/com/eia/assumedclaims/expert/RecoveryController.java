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
@RequestMapping("/internal/v1/assumed-claims/{claimId}/recoveries")
public class RecoveryController {
    private final RecoveryService recoveries;

    public RecoveryController(RecoveryService recoveries) {
        this.recoveries = recoveries;
    }

    @GetMapping
    public List<Recovery> list(
            @PathVariable UUID claimId,
            @RequestHeader("X-EIA-Tenant") UUID tenantId) {
        return recoveries.list(tenantId, claimId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Recovery record(
            @PathVariable UUID claimId,
            @RequestHeader("X-EIA-Tenant") UUID tenantId,
            @RequestHeader(value = "X-EIA-User", required = false) String actor,
            @Valid @RequestBody RecordRecoveryRequest request) {
        return recoveries.create(tenantId, claimId, request, AssumedClaimService.actor(actor));
    }
}
