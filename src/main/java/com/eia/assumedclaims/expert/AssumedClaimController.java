package com.eia.assumedclaims.expert;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/assumed-claims")
public class AssumedClaimController {
    private final AssumedClaimService service;
    private final FinancialService financials;

    public AssumedClaimController(AssumedClaimService service, FinancialService financials) {
        this.service = service;
        this.financials = financials;
    }

    @GetMapping
    public List<AssumedClaim> list(@RequestParam UUID tenantId) {
        return service.list(tenantId);
    }

    @GetMapping("/{id}")
    public AssumedClaim get(@RequestParam UUID tenantId, @PathVariable UUID id) {
        return service.get(tenantId, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AssumedClaim create(
            @Valid @RequestBody CreateAssumedClaimRequest request,
            @RequestHeader(value = "X-EIA-User", required = false) String actor) {
        AssumedClaimRules.validateCreate(request);
        return service.create(request, AssumedClaimService.actor(actor));
    }

    @PostMapping("/{id}/status")
    public AssumedClaim changeStatus(
            @PathVariable UUID id,
            @Valid @RequestBody StatusChangeRequest request,
            @RequestHeader(value = "X-EIA-User", required = false) String actor) {
        return service.changeStatus(
                request.tenantId(), id, request.targetStatus(), request.expectedVersion(), AssumedClaimService.actor(actor));
    }

    @PostMapping("/{id}/close")
    public AssumedClaim close(
            @PathVariable UUID id,
            @RequestHeader("X-EIA-Tenant") UUID tenantId,
            @RequestHeader(value = "X-EIA-User", required = false) String actor) {
        return service.close(tenantId, id, AssumedClaimService.actor(actor));
    }

    @GetMapping("/{id}/financials")
    public FinancialSummary financials(
            @PathVariable UUID id,
            @RequestHeader("X-EIA-Tenant") UUID tenantId) {
        return financials.summary(tenantId, id);
    }

    @GetMapping("/{id}/audit")
    public List<ClaimAuditEntry> audit(
            @PathVariable UUID id,
            @RequestHeader("X-EIA-Tenant") UUID tenantId) {
        return service.audit(tenantId, id);
    }
}
