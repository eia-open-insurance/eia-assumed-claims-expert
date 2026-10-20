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

/**
 * Append-only claim notes. The public route is
 * {@code /api/v1/assumed-claims/claims/{claimId}/notes}; the shorter
 * {@code /api/v1/assumed-claims/{claimId}/notes} and the internal
 * {@code /internal/v1/assumed-claims/{claimId}/notes} aliases match the other
 * Assumed Claims child routes. There is no update or delete route. Every route
 * is tenant-scoped and returns 404 outside the ASSUMED book.
 */
@RestController
@RequestMapping({
        "/api/v1/assumed-claims/claims/{claimId}/notes",
        "/api/v1/assumed-claims/{claimId}/notes",
        "/internal/v1/assumed-claims/{claimId}/notes"
})
public class ClaimNoteController {
    private final ClaimNoteService service;

    public ClaimNoteController(ClaimNoteService service) {
        this.service = service;
    }

    @GetMapping
    public List<ClaimNote> list(@RequestParam(value = "tenantId", required = false) UUID tenantId,
                                @RequestHeader(value = "X-EIA-Tenant", required = false) UUID tenantHeader,
                                @RequestHeader(value = "X-EIA-Book", required = false) String book,
                                @PathVariable UUID claimId) {
        BookWall.requireAssumed(book);
        return service.list(TenantHeader.resolve(tenantId, tenantHeader), claimId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimNote add(@RequestHeader("X-EIA-Tenant") UUID tenantId,
                         @RequestHeader(value = "X-EIA-Book", required = false) String book,
                         @PathVariable UUID claimId,
                         @Valid @RequestBody CreateNoteRequest request) {
        BookWall.requireAssumed(book);
        return service.add(tenantId, claimId, request);
    }
}
