package com.eia.assumedclaims.expert;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract for PATCH claim assignment: canonical route, aliases, ASSUMED
 * book wall, bean validation, and the 409 mapping.
 */
@WebMvcTest(AssumedClaimAssignmentController.class)
class AssumedClaimAssignmentEndpointTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID CONTRACT = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID CLAIM = UUID.fromString("0192f3a4-5b1c-7e2d-9a3f-6c8d1e0b4a72");
    private static final String BASE = "/api/v1/assumed-claims/claims/" + CLAIM + "/assignment";
    private static final String SHORT_BASE = "/api/v1/assumed-claims/" + CLAIM + "/assignment";
    private static final String INTERNAL_BASE = "/internal/v1/assumed-claims/" + CLAIM + "/assignment";
    private static final String ASSUMED = BookScopeFilter.ASSUMED_BOOK;
    private static final String DIRECT = "DIRECT";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AssumedClaimService service;

    @Test
    void assignsThroughTheCanonicalPublicRoute() throws Exception {
        given(service.assign(eq(TENANT), eq(CLAIM), any(ClaimAssignmentRequest.class),
                eq("adjuster@example.com"))).willReturn(claim("A. Adjuster", "CASUALTY", 2));

        mvc.perform(patch(BASE)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", ASSUMED)
                        .header("X-EIA-Actor", "adjuster@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "assignedAdjuster": "A. Adjuster",
                                  "assignedTeam": "CASUALTY",
                                  "expectedVersion": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedAdjuster").value("A. Adjuster"))
                .andExpect(jsonPath("$.assignedTeam").value("CASUALTY"))
                .andExpect(jsonPath("$.assignedAt").value("2026-10-20T12:00:00Z"))
                .andExpect(jsonPath("$.version").value(2));

        verify(service).assign(eq(TENANT), eq(CLAIM), any(ClaimAssignmentRequest.class),
                eq("adjuster@example.com"));
    }

    @Test
    void reassignsThroughTheShortPublicAlias() throws Exception {
        given(service.assign(eq(TENANT), eq(CLAIM), any(ClaimAssignmentRequest.class), eq("lead")))
                .willReturn(claim("B. Examiner", "PROPERTY", 5));

        mvc.perform(patch(SHORT_BASE)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", ASSUMED)
                        .header("X-EIA-Actor", "lead")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "assignedAdjuster": "B. Examiner",
                                  "assignedTeam": "PROPERTY",
                                  "expectedVersion": 4
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedAdjuster").value("B. Examiner"))
                .andExpect(jsonPath("$.assignedTeam").value("PROPERTY"))
                .andExpect(jsonPath("$.version").value(5));
    }

    @Test
    void assignsThroughTheInternalAlias() throws Exception {
        given(service.assign(eq(TENANT), eq(CLAIM), any(ClaimAssignmentRequest.class), isNull()))
                .willReturn(claim("A. Adjuster", "CASUALTY", 2));

        mvc.perform(patch(INTERNAL_BASE)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", ASSUMED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "assignedAdjuster": "A. Adjuster",
                                  "assignedTeam": "CASUALTY",
                                  "expectedVersion": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedAdjuster").value("A. Adjuster"));
    }

    @Test
    void aStaleVersionReturnsAnOptimisticConflict() throws Exception {
        given(service.assign(eq(TENANT), eq(CLAIM), any(ClaimAssignmentRequest.class), isNull()))
                .willThrow(ApiException.versionConflict(1));

        mvc.perform(patch(BASE)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", ASSUMED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "assignedAdjuster": "A. Adjuster",
                                  "assignedTeam": "CASUALTY",
                                  "expectedVersion": 1
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EIA-RNS-7006"))
                .andExpect(jsonPath("$.category").value("CONFLICT"));
    }

    @Test
    void aSameAssignmentNoOpStillReturnsTheCurrentClaim() throws Exception {
        given(service.assign(eq(TENANT), eq(CLAIM), any(ClaimAssignmentRequest.class), isNull()))
                .willReturn(claim("A. Adjuster", "CASUALTY", 2));

        mvc.perform(patch(BASE)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", ASSUMED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "assignedAdjuster": "A. Adjuster",
                                  "assignedTeam": "CASUALTY",
                                  "expectedVersion": 2
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedAdjuster").value("A. Adjuster"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void missingAssignmentFieldsAreRejectedBeforeTheServiceCall() throws Exception {
        mvc.perform(patch(BASE)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", ASSUMED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EIA-RNS-7004"))
                .andExpect(jsonPath("$.errors[*].field",
                        hasItems("assignedAdjuster", "expectedVersion")));

        verifyNoInteractions(service);
    }

    @Test
    void aTeamIsOptionalAndTheAdjusterIsRecordedAsTheActor() throws Exception {
        given(service.assign(eq(TENANT), eq(CLAIM), any(ClaimAssignmentRequest.class), isNull()))
                .willReturn(claim("A. Adjuster", null, 2));

        mvc.perform(patch(BASE)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", ASSUMED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "assignedAdjuster": "A. Adjuster",
                                  "expectedVersion": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedAdjuster").value("A. Adjuster"))
                .andExpect(jsonPath("$.assignedTeam").value(nullValue()))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void anOverlongAdjusterIsRejected() throws Exception {
        mvc.perform(patch(BASE)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", ASSUMED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "assignedAdjuster": "%s",
                                  "assignedTeam": "CASUALTY",
                                  "expectedVersion": 1
                                }
                                """.formatted("A".repeat(129))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EIA-RNS-7004"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("assignedAdjuster")));

        verifyNoInteractions(service);
    }

    @Test
    void blankAdjusterAndNegativeVersionAreRejected() throws Exception {
        mvc.perform(patch(BASE)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", ASSUMED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "assignedAdjuster": "   ",
                                  "assignedTeam": "CASUALTY",
                                  "expectedVersion": -1
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EIA-RNS-7004"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("assignedAdjuster")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("expectedVersion")));

        verifyNoInteractions(service);
    }

    @Test
    void assignmentStaysInsideTheAssumedBookWall() throws Exception {
        mvc.perform(patch(BASE)
                        .header("X-EIA-Tenant", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody(1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EIA-RNS-7005"));

        mvc.perform(patch(BASE)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", DIRECT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody(1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EIA-RNS-7005"));

        mvc.perform(patch(SHORT_BASE)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", DIRECT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody(1)))
                .andExpect(status().isNotFound());

        mvc.perform(patch(INTERNAL_BASE)
                        .header("X-EIA-Tenant", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody(1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EIA-RNS-7005"));

        mvc.perform(patch(INTERNAL_BASE)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", DIRECT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody(1)))
                .andExpect(status().isNotFound());

        verifyNoInteractions(service);
    }

    @Test
    void aMissingClaimIs404() throws Exception {
        given(service.assign(eq(TENANT), eq(CLAIM), any(ClaimAssignmentRequest.class), isNull()))
                .willThrow(ApiException.assumedClaimNotFound(CLAIM));

        mvc.perform(patch(BASE)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", ASSUMED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody(0)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EIA-RNS-7001"));
    }

    @Test
    void aMalformedBodyIs400() throws Exception {
        mvc.perform(patch(BASE)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", ASSUMED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EIA-RNS-7004"));

        verifyNoInteractions(service);
    }

    private static String validBody(int expectedVersion) {
        return """
                {
                  "assignedAdjuster": "A. Adjuster",
                  "assignedTeam": "CASUALTY",
                  "expectedVersion": %d
                }
                """.formatted(expectedVersion);
    }

    private static AssumedClaim claim(String adjuster, String team, int version) {
        Instant now = Instant.parse("2026-10-20T12:00:00Z");
        return new AssumedClaim(
                CLAIM,
                TENANT,
                "ASSUMED",
                "RNS-2026-0007731",
                CONTRACT,
                "CED-778",
                AssumedClaimStatus.OPEN,
                LocalDate.parse("2026-09-01"),
                LocalDate.parse("2026-09-02"),
                new BigDecimal("12500.0000"),
                "USD",
                adjuster,
                team,
                now,
                version,
                now,
                now);
    }
}

