package com.eia.assumedclaims.expert;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/internal/v1/assumed-claims/{claimId}")
public class LossAdviceController {
    private final LossAdviceService advices;
    private final BordereauService bordereaux;

    public LossAdviceController(LossAdviceService advices, BordereauService bordereaux) {
        this.advices = advices;
        this.bordereaux = bordereaux;
    }

    @GetMapping("/advices")
    public List<LossAdvice> list(
            @PathVariable UUID claimId,
            @RequestHeader("X-EIA-Tenant") UUID tenantId) {
        return advices.list(tenantId, claimId);
    }

    @PostMapping("/advices")
    @ResponseStatus(HttpStatus.CREATED)
    public LossAdvice record(
            @PathVariable UUID claimId,
            @RequestHeader("X-EIA-Tenant") UUID tenantId,
            @RequestHeader(value = "X-EIA-User", required = false) String actor,
            @Valid @RequestBody RecordAdviceRequest request) {
        return advices.record(tenantId, claimId, request, AssumedClaimService.actor(actor));
    }

    @PostMapping("/advices/{adviceId}/accept")
    public LossAdvice accept(
            @PathVariable UUID claimId,
            @PathVariable UUID adviceId,
            @RequestHeader("X-EIA-Tenant") UUID tenantId,
            @RequestHeader(value = "X-EIA-User", required = false) String actor,
            @RequestBody(required = false) AcceptAdviceRequest request) {
        return advices.accept(tenantId, claimId, adviceId, request, AssumedClaimService.actor(actor));
    }

    @GetMapping("/bordereaux")
    public List<BordereauBatch> listBordereaux(
            @PathVariable UUID claimId,
            @RequestHeader("X-EIA-Tenant") UUID tenantId) {
        return bordereaux.list(tenantId, claimId);
    }

    @PostMapping("/bordereaux")
    public ResponseEntity<BordereauIngestResult> ingest(
            @PathVariable UUID claimId,
            @RequestHeader("X-EIA-Tenant") UUID tenantId,
            @RequestHeader(value = "X-EIA-User", required = false) String actor,
            @Valid @RequestBody IngestBordereauRequest request) {
        BordereauIngestResult result = bordereaux.ingest(tenantId, claimId, request, AssumedClaimService.actor(actor));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
    }
}
