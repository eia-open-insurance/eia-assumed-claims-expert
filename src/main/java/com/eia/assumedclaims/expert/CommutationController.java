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

import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/assumed-claims/{claimId}")
public class CommutationController {
    private final CommutationService commutations;

    public CommutationController(CommutationService commutations) {
        this.commutations = commutations;
    }

    @GetMapping("/commutation")
    public Commutation get(
            @PathVariable UUID claimId,
            @RequestHeader("X-EIA-Tenant") UUID tenantId) {
        return commutations.get(tenantId, claimId);
    }

    @PostMapping("/commute")
    @ResponseStatus(HttpStatus.CREATED)
    public Commutation commute(
            @PathVariable UUID claimId,
            @RequestHeader("X-EIA-Tenant") UUID tenantId,
            @RequestHeader(value = "X-EIA-User", required = false) String actor,
            @Valid @RequestBody CommuteClaimRequest request) {
        return commutations.execute(tenantId, claimId, request, AssumedClaimService.actor(actor));
    }
}
