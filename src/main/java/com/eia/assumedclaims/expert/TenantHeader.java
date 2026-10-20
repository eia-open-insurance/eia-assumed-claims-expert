package com.eia.assumedclaims.expert;

import java.util.List;
import java.util.UUID;

/**
 * Resolves the tenant for read routes. The internal expert convention passes
 * {@code ?tenantId=}, while the public {@code /api/v1} convention passes
 * {@code X-EIA-Tenant}; both are accepted so neither client is broken.
 */
final class TenantHeader {
    private TenantHeader() {
    }

    static UUID resolve(UUID tenantIdParam, UUID tenantHeader) {
        UUID tenantId = tenantIdParam != null ? tenantIdParam : tenantHeader;
        if (tenantId == null) {
            throw ApiException.validation("tenantId is required",
                    List.of(new FieldIssue("tenantId", "REQUIRED", "tenantId is required")));
        }
        return tenantId;
    }
}
