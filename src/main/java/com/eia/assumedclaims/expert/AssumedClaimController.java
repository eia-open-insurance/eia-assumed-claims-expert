package com.eia.assumedclaims.expert;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/assumed-claims")
public class AssumedClaimController {
    private final AssumedClaimRepository repository;

    public AssumedClaimController(AssumedClaimRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<AssumedClaim> list(@RequestParam UUID tenantId) {
        return repository.findAll(tenantId);
    }

    @GetMapping("/{id}")
    public AssumedClaim get(@RequestParam UUID tenantId, @PathVariable UUID id) {
        return repository.findById(tenantId, id)
                .orElseThrow(() -> ApiException.assumedClaimNotFound(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AssumedClaim create(@Valid @RequestBody CreateAssumedClaimRequest request) {
        AssumedClaimRules.validateCreate(request);
        return repository.create(request);
    }

    @PostMapping("/{id}/status")
    public AssumedClaim changeStatus(@PathVariable UUID id, @Valid @RequestBody StatusChangeRequest request) {
        return repository.changeStatus(request.tenantId(), id, request.targetStatus(), request.expectedVersion());
    }
}
