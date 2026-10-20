package com.eia.assumedclaims.expert;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Claim assignment. The public route is
 * {@code /api/v1/assumed-claims/claims/{claimId}/assignment}; the shorter
 * {@code /api/v1/assumed-claims/{claimId}/assignment} and the internal
 * {@code /internal/v1/assumed-claims/{claimId}/assignment} aliases match the
 * other Assumed Claims child routes.
 *
 * <p>Every route is tenant-scoped and stays inside the ASSUMED book wall: a
 * missing or different {@code X-EIA-Book} is answered with 404, never 403, so
 * the route cannot confirm that a DIRECT claim exists (SPEC §3.6.2). The
 * assignee is taken from the body; the optional {@code X-EIA-Actor} header is
 * the platform actor convention shared with Direct Claims and is recorded as
 * {@code assigned_by} and in the audit trail. When the header is absent the
 * assigned adjuster is recorded as the actor.</p>
 */
@RestController
@RequestMapping({
        "/api/v1/assumed-claims/claims/{claimId}/assignment",
        "/api/v1/assumed-claims/{claimId}/assignment",
        "/internal/v1/assumed-claims/{claimId}/assignment"
})
public class AssumedClaimAssignmentController {
    private final AssumedClaimService service;

    public AssumedClaimAssignmentController(AssumedClaimService service) {
        this.service = service;
    }

    @PatchMapping
    public AssumedClaim assign(
            @RequestHeader("X-EIA-Tenant") UUID tenantId,
            @RequestHeader(value = BookScopeFilter.BOOK_HEADER, required = false) String book,
            @RequestHeader(value = "X-EIA-Actor", required = false) String actor,
            @PathVariable UUID claimId,
            @Valid @RequestBody ClaimAssignmentRequest request) {
        BookWall.requireAssumed(book);
        return service.assign(tenantId, claimId, request, actor);
    }
}
