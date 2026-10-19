package com.eia.assumedclaims.expert;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AssumedClaimController.class)
class AssumedClaimControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID CONTRACT = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID CLAIM = UUID.fromString("0192f3a4-5b1c-7e2d-9a3f-6c8d1e0b4a72");
    private static final String ASSUMED = "ASSUMED";
    private static final String DIRECT = "DIRECT";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AssumedClaimRepository repository;

    @Test
    void listRequiresAssumedBookAndReturns404WhenMissing() throws Exception {
        mvc.perform(get("/internal/v1/assumed-claims").param("tenantId", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EIA-RNS-7005"))
                .andExpect(jsonPath("$.status").value(404));

        verifyNoInteractions(repository);
    }

    @Test
    void listRejectsDirectBookWith404() throws Exception {
        mvc.perform(get("/internal/v1/assumed-claims")
                        .header(BookScopeFilter.BOOK_HEADER, DIRECT)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EIA-RNS-7005"));

        verifyNoInteractions(repository);
    }

    @Test
    void listReturnsAssumedClaims() throws Exception {
        given(repository.findAll(TENANT)).willReturn(List.of(claim(AssumedClaimStatus.NOTIFIED, 0)));

        mvc.perform(get("/internal/v1/assumed-claims")
                        .header(BookScopeFilter.BOOK_HEADER, ASSUMED)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].assumedClaimNumber").value("RNS-2026-0007731"))
                .andExpect(jsonPath("$[0].businessBook").value(ASSUMED))
                .andExpect(jsonPath("$[0].status").value("NOTIFIED"))
                .andExpect(jsonPath("$[0].reserveAmount").value("12500.0000"))
                .andExpect(jsonPath("$[0].reserveAmount").isString());
    }

    @Test
    void reserveAmountIsSerializedAsDecimalString() throws Exception {
        given(repository.findAll(TENANT)).willReturn(List.of(claim(AssumedClaimStatus.NOTIFIED, 0)));

        mvc.perform(get("/internal/v1/assumed-claims")
                        .header(BookScopeFilter.BOOK_HEADER, ASSUMED)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reserveAmount").isString())
                .andExpect(jsonPath("$[0].reserveAmount").value("12500.0000"));
    }

    @Test
    void getReturnsStructured404WhenMissing() throws Exception {
        given(repository.findById(TENANT, CLAIM)).willReturn(Optional.empty());

        mvc.perform(get("/internal/v1/assumed-claims/{id}", CLAIM)
                        .header(BookScopeFilter.BOOK_HEADER, ASSUMED)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EIA-RNS-7001"))
                .andExpect(jsonPath("$.category").value("NOT_FOUND"));
    }

    @Test
    void createPersistsClaimAsNotified() throws Exception {
        given(repository.create(any())).willReturn(claim(AssumedClaimStatus.NOTIFIED, 0));

        mvc.perform(post("/internal/v1/assumed-claims")
                        .header(BookScopeFilter.BOOK_HEADER, ASSUMED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantId", TENANT.toString(),
                                "contractId", CONTRACT.toString(),
                                "cedentClaimNumber", "CED-778",
                                "lossDate", "2026-09-01",
                                "reportDate", "2026-09-02",
                                "reserveAmount", 12500.00,
                                "reserveCurrency", "USD"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NOTIFIED"))
                .andExpect(jsonPath("$.assumedClaimNumber").value("RNS-2026-0007731"));

        verify(repository).create(any());
    }

    @Test
    void createRejectsReportDateBeforeLossDate() throws Exception {
        mvc.perform(post("/internal/v1/assumed-claims")
                        .header(BookScopeFilter.BOOK_HEADER, ASSUMED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantId", TENANT.toString(),
                                "contractId", CONTRACT.toString(),
                                "lossDate", "2026-09-10",
                                "reportDate", "2026-09-01",
                                "reserveAmount", 100.00,
                                "reserveCurrency", "USD"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EIA-RNS-7004"))
                .andExpect(jsonPath("$.errors[0].field").value("reportDate"))
                .andExpect(jsonPath("$.errors[0].code").value("REPORT_BEFORE_LOSS"));

        verify(repository, never()).create(any());
    }

    @Test
    void createRejectsNegativeReserve() throws Exception {
        mvc.perform(post("/internal/v1/assumed-claims")
                        .header(BookScopeFilter.BOOK_HEADER, ASSUMED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantId", TENANT.toString(),
                                "contractId", CONTRACT.toString(),
                                "reserveAmount", -1.00,
                                "reserveCurrency", "USD"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EIA-RNS-7004"))
                .andExpect(jsonPath("$.errors[0].field").value("reserveAmount"));

        verify(repository, never()).create(any());
    }

    @Test
    void createRejectsMissingContract() throws Exception {
        mvc.perform(post("/internal/v1/assumed-claims")
                        .header(BookScopeFilter.BOOK_HEADER, ASSUMED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantId", TENANT.toString(),
                                "reserveAmount", 100.00,
                                "reserveCurrency", "USD"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EIA-RNS-7004"))
                .andExpect(jsonPath("$.errors[0].field").value("contractId"));
    }

    @Test
    void transitionAppliesAllowedStatus() throws Exception {
        given(repository.changeStatus(TENANT, CLAIM, AssumedClaimStatus.RESERVED, 2))
                .willReturn(claim(AssumedClaimStatus.RESERVED, 3));

        mvc.perform(post("/internal/v1/assumed-claims/{id}/status", CLAIM)
                        .header(BookScopeFilter.BOOK_HEADER, ASSUMED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantId", TENANT.toString(),
                                "targetStatus", "RESERVED",
                                "expectedVersion", 2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.version").value(3));
    }

    @Test
    void transitionRejectsIllegalStatusWith409() throws Exception {
        given(repository.changeStatus(TENANT, CLAIM, AssumedClaimStatus.PAID, 2))
                .willThrow(ApiException.invalidTransition(AssumedClaimStatus.OPEN, AssumedClaimStatus.PAID));

        mvc.perform(post("/internal/v1/assumed-claims/{id}/status", CLAIM)
                        .header(BookScopeFilter.BOOK_HEADER, ASSUMED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantId", TENANT.toString(),
                                "targetStatus", "PAID",
                                "expectedVersion", 2))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EIA-RNS-7003"))
                .andExpect(jsonPath("$.category").value("CONFLICT"));
    }

    @Test
    void transitionRejectsStaleVersionWith409() throws Exception {
        given(repository.changeStatus(TENANT, CLAIM, AssumedClaimStatus.OPEN, 1))
                .willThrow(ApiException.versionConflict(1));

        mvc.perform(post("/internal/v1/assumed-claims/{id}/status", CLAIM)
                        .header(BookScopeFilter.BOOK_HEADER, ASSUMED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantId", TENANT.toString(),
                                "targetStatus", "OPEN",
                                "expectedVersion", 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EIA-RNS-7006"));
    }

    private static AssumedClaim claim(AssumedClaimStatus status, int version) {
        Instant now = Instant.parse("2026-10-19T12:00:00Z");
        return new AssumedClaim(
                CLAIM,
                TENANT,
                "ASSUMED",
                "RNS-2026-0007731",
                CONTRACT,
                "CED-778",
                status,
                LocalDate.parse("2026-09-01"),
                LocalDate.parse("2026-09-02"),
                new BigDecimal("12500.0000"),
                "USD",
                version,
                now,
                now
        );
    }
}
