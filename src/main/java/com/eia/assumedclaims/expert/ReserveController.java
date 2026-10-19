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
@RequestMapping("/internal/v1/assumed-claims/{claimId}/reserves")
public class ReserveController {
    private final ReserveService reserves;

    public ReserveController(ReserveService reserves) {
        this.reserves = reserves;
    }

    @GetMapping
    public List<ReserveHistoryEntry> list(
            @PathVariable UUID claimId,
            @RequestHeader("X-EIA-Tenant") UUID tenantId) {
        return reserves.list(tenantId, claimId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReserveHistoryEntry change(
            @PathVariable UUID claimId,
            @RequestHeader("X-EIA-Tenant") UUID tenantId,
            @RequestHeader(value = "X-EIA-User", required = false) String actor,
            @RequestHeader(value = "X-EIA-User-Id", required = false) UUID userId,
            @Valid @RequestBody ReserveChangeRequest request) {
        return reserves.change(tenantId, claimId, request, userId, AssumedClaimService.actor(actor));
    }
}
