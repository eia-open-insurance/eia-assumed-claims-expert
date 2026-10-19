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
@RequestMapping("/internal/v1/assumed-claims/{claimId}/disputes")
public class DisputeController {
    private final DisputeService disputes;

    public DisputeController(DisputeService disputes) {
        this.disputes = disputes;
    }

    @GetMapping
    public List<Dispute> list(
            @PathVariable UUID claimId,
            @RequestHeader("X-EIA-Tenant") UUID tenantId) {
        return disputes.list(tenantId, claimId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Dispute raise(
            @PathVariable UUID claimId,
            @RequestHeader("X-EIA-Tenant") UUID tenantId,
            @RequestHeader(value = "X-EIA-User", required = false) String actor,
            @Valid @RequestBody RaiseDisputeRequest request) {
        return disputes.raise(tenantId, claimId, request, AssumedClaimService.actor(actor));
    }

    @PostMapping("/{disputeId}/resolve")
    public Dispute resolve(
            @PathVariable UUID claimId,
            @PathVariable UUID disputeId,
            @RequestHeader("X-EIA-Tenant") UUID tenantId,
            @RequestHeader(value = "X-EIA-User", required = false) String actor,
            @Valid @RequestBody ResolveDisputeRequest request) {
        return disputes.resolve(tenantId, claimId, disputeId, request, AssumedClaimService.actor(actor));
    }
}
