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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        LossAdviceController.class,
        ReserveController.class,
        CashCallController.class,
        RecoveryController.class,
        CommutationController.class,
        DisputeController.class
})
class SecondSliceControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID CLAIM = UUID.fromString("0192f3a4-5b1c-7e2d-9a3f-6c8d1e0b4a72");
    private static final UUID ADVICE = UUID.fromString("0192f3a4-aaaa-7aaa-8aaa-000000000001");
    private static final UUID APPROVER = UUID.fromString("0192f3a4-bbbb-7bbb-8bbb-000000000002");
    private static final UUID DOCUMENT = UUID.fromString("0192f3a4-cccc-7ccc-8ccc-000000000003");
    private static final String BOOK = BookScopeFilter.BOOK_HEADER;
    private static final String ASSUMED = "ASSUMED";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private LossAdviceService adviceService;

    @MockitoBean
    private BordereauService bordereauService;

    @MockitoBean
    private ReserveService reserveService;

    @MockitoBean
    private CashCallService cashCallService;

    @MockitoBean
    private RecoveryService recoveryService;

    @MockitoBean
    private CommutationService commutationService;

    @MockitoBean
    private DisputeService disputeService;

    @Test
    void newEndpointsObeyTheAssumedBookWall() throws Exception {
        mvc.perform(get("/internal/v1/assumed-claims/{id}/advices", CLAIM)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EIA-RNS-7005"));
        verifyNoInteractions(adviceService);
    }

    @Test
    void newEndpointsRequireTenantHeader() throws Exception {
        mvc.perform(get("/internal/v1/assumed-claims/{id}/advices", CLAIM).header(BOOK, ASSUMED))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EIA-PLT-0201"));
    }

    @Test
    void listAdvicesSerializesMoneyAsStrings() throws Exception {
        given(adviceService.list(TENANT, CLAIM)).willReturn(List.of(advice(AdviceStatus.RECEIVED)));

        mvc.perform(get("/internal/v1/assumed-claims/{id}/advices", CLAIM)
                        .header(BOOK, ASSUMED)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].adviceNumber").value("ADV-001"))
                .andExpect(jsonPath("$[0].reportedAmount").value("250000.0000"))
                .andExpect(jsonPath("$[0].reportedAmount").isString());
    }

    @Test
    void recordAdviceReturns201() throws Exception {
        given(adviceService.record(eq(TENANT), eq(CLAIM), any(), eq("system")))
                .willReturn(advice(AdviceStatus.RECEIVED));

        mvc.perform(post("/internal/v1/assumed-claims/{id}/advices", CLAIM)
                        .header(BOOK, ASSUMED)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "adviceNumber", "ADV-001",
                                "adviceType", "INITIAL_NOTICE",
                                "adviceDate", "2026-09-03",
                                "reportedAmount", "250000.00",
                                "currency", "USD"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RECEIVED"));
    }

    @Test
    void duplicateAdviceReturns409() throws Exception {
        given(adviceService.record(eq(TENANT), eq(CLAIM), any(), any()))
                .willThrow(ApiException.duplicateAdvice("ADV-001"));

        mvc.perform(post("/internal/v1/assumed-claims/{id}/advices", CLAIM)
                        .header(BOOK, ASSUMED)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "adviceNumber", "ADV-001",
                                "adviceType", "INITIAL_NOTICE",
                                "adviceDate", "2026-09-03",
                                "reportedAmount", "250000.00",
                                "currency", "USD"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EIA-RNS-7101"));
    }

    @Test
    void acceptAdviceUpdatesStatus() throws Exception {
        given(adviceService.accept(eq(TENANT), eq(CLAIM), eq(ADVICE), any(), eq("system")))
                .willReturn(advice(AdviceStatus.POSTED));

        mvc.perform(post("/internal/v1/assumed-claims/{id}/advices/{adviceId}/accept", CLAIM, ADVICE)
                        .header(BOOK, ASSUMED)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"));
    }

    @Test
    void ingestBordereauReportsDuplicateLines() throws Exception {
        BordereauBatch batch = batch(BordereauStatus.PARTIAL, 1, 1, 0);
        given(bordereauService.ingest(eq(TENANT), eq(CLAIM), any(), eq("system")))
                .willReturn(new BordereauIngestResult(batch, List.of(
                        new BordereauIngestResult.LineResult(1, "B-1", BordereauLineStatus.ACCEPTED, null, ADVICE,
                                new BigDecimal("100.0000")),
                        new BordereauIngestResult.LineResult(2, "B-2", BordereauLineStatus.DUPLICATE,
                                "duplicate", null, new BigDecimal("50.0000"))), false));

        mvc.perform(post("/internal/v1/assumed-claims/{id}/bordereaux", CLAIM)
                        .header(BOOK, ASSUMED)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bordereauReference", "BDX-2026-01",
                                "adviceDate", "2026-09-03",
                                "currency", "USD",
                                "lines", List.of(
                                        Map.of("adviceNumber", "B-1", "reportedAmount", "100.00"),
                                        Map.of("adviceNumber", "B-2", "reportedAmount", "50.00"))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batch.status").value("PARTIAL"))
                .andExpect(jsonPath("$.lines[1].status").value("DUPLICATE"));
    }

    @Test
    void listBordereauxReturnsBatchHistory() throws Exception {
        given(bordereauService.list(TENANT, CLAIM))
                .willReturn(List.of(batch(BordereauStatus.PROCESSED, 2, 0, 0)));

        mvc.perform(get("/internal/v1/assumed-claims/{id}/bordereaux", CLAIM)
                        .header(BOOK, ASSUMED)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bordereauReference").value("BDX-2026-01"))
                .andExpect(jsonPath("$[0].acceptedCount").value(2));
    }

    @Test
    void listCashCallsReturnsRequests() throws Exception {
        given(cashCallService.list(TENANT, CLAIM)).willReturn(List.of(new CashCall(
                UUID.randomUUID(), TENANT, CLAIM, "CSH-2026-000001", new BigDecimal("50000.0000"), "USD",
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-15"), CashCallStatus.SUBMITTED,
                CashCallReason.CASH_LOSS, null, null, null, null, "NOT_REQUIRED", null, 1,
                Instant.parse("2026-10-19T12:00:00Z"), Instant.parse("2026-10-19T12:00:00Z"))));

        mvc.perform(get("/internal/v1/assumed-claims/{id}/cash-calls", CLAIM)
                        .header(BOOK, ASSUMED)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].requestNumber").value("CSH-2026-000001"))
                .andExpect(jsonPath("$[0].requestedAmount").value("50000.0000"))
                .andExpect(jsonPath("$[0].requestedAmount").isString());
    }

    @Test
    void listRecoveriesReturnsRecoveries() throws Exception {
        given(recoveryService.list(TENANT, CLAIM)).willReturn(List.of(new Recovery(
                UUID.randomUUID(), TENANT, CLAIM, RecoveryType.SALVAGE, new BigDecimal("25000.0000"), "USD",
                LocalDate.parse("2026-10-01"), RecoveryStatus.RECEIVED, null, null, null, null, 1,
                Instant.parse("2026-10-19T12:00:00Z"), Instant.parse("2026-10-19T12:00:00Z"))));

        mvc.perform(get("/internal/v1/assumed-claims/{id}/recoveries", CLAIM)
                        .header(BOOK, ASSUMED)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].recoveryType").value("SALVAGE"))
                .andExpect(jsonPath("$[0].amount").value("25000.0000"));
    }

    @Test
    void listDisputesReturnsDisputes() throws Exception {
        given(disputeService.list(TENANT, CLAIM)).willReturn(List.of(dispute(DisputeStatus.OPEN)));

        mvc.perform(get("/internal/v1/assumed-claims/{id}/disputes", CLAIM)
                        .header(BOOK, ASSUMED)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].disputeNumber").value("DSP-2026-000001"))
                .andExpect(jsonPath("$[0].status").value("OPEN"));
    }

    @Test
    void listReserveHistoryIncludesDerivedMoney() throws Exception {
        given(reserveService.list(TENANT, CLAIM)).willReturn(List.of(reserve(1, "100000.0000", true)));

        mvc.perform(get("/internal/v1/assumed-claims/{id}/reserves", CLAIM)
                        .header(BOOK, ASSUMED)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].component").value("CASE"))
                .andExpect(jsonPath("$[0].amount").value("100000.0000"))
                .andExpect(jsonPath("$[0].amount").isString())
                .andExpect(jsonPath("$[0].isCurrent").value(true));
    }

    @Test
    void reserveRequiresReasonAndEffectiveDate() throws Exception {
        mvc.perform(post("/internal/v1/assumed-claims/{id}/reserves", CLAIM)
                        .header(BOOK, ASSUMED)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "component", "CASE",
                                "amount", "100.00",
                                "currency", "USD",
                                "authority", "ADJUSTER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EIA-RNS-7004"));
    }

    @Test
    void reserveAuthorityRequiredReturns428() throws Exception {
        given(reserveService.change(eq(TENANT), eq(CLAIM), any(), any(), any()))
                .willThrow(ApiException.reserveAuthorityRequired("named approver required"));

        mvc.perform(post("/internal/v1/assumed-claims/{id}/reserves", CLAIM)
                        .header(BOOK, ASSUMED)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "component", "CASE",
                                "amount", "2000000.00",
                                "currency", "USD",
                                "effectiveDate", "2026-10-01",
                                "reasonCode", "REVIEW",
                                "authority", "ADJUSTER"))))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("EIA-RNS-7203"));
    }

    @Test
    void staleReserveDateReturns409() throws Exception {
        given(reserveService.change(eq(TENANT), eq(CLAIM), any(), any(), any()))
                .willThrow(ApiException.reserveStale(ReserveComponent.CASE,
                        LocalDate.parse("2026-10-02"), LocalDate.parse("2026-10-01")));

        mvc.perform(post("/internal/v1/assumed-claims/{id}/reserves", CLAIM)
                        .header(BOOK, ASSUMED)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "component", "CASE",
                                "amount", "100.00",
                                "currency", "USD",
                                "effectiveDate", "2026-10-01",
                                "reasonCode", "REVIEW",
                                "authority", "ADJUSTER"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EIA-RNS-7202"));
    }

    @Test
    void cashCallOverReserveReturns422() throws Exception {
        given(cashCallService.create(eq(TENANT), eq(CLAIM), any(), any()))
                .willThrow(ApiException.cashCallExceedsReserve("exceeds available reserve"));

        mvc.perform(post("/internal/v1/assumed-claims/{id}/cash-calls", CLAIM)
                        .header(BOOK, ASSUMED)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestedAmount", "500000.00",
                                "currency", "USD",
                                "reason", "LARGE_LOSS"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EIA-RNS-7301"));
    }

    @Test
    void cashCallWithoutApprovalReturns428() throws Exception {
        given(cashCallService.create(eq(TENANT), eq(CLAIM), any(), any()))
                .willThrow(ApiException.cashCallApprovalRequired("approval required"));

        mvc.perform(post("/internal/v1/assumed-claims/{id}/cash-calls", CLAIM)
                        .header(BOOK, ASSUMED)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestedAmount", "300000.00",
                                "currency", "USD",
                                "reason", "LARGE_LOSS"))))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("EIA-RNS-7302"));
    }

    @Test
    void recoveryOverAdvisedLossReturns422() throws Exception {
        given(recoveryService.create(eq(TENANT), eq(CLAIM), any(), any()))
                .willThrow(ApiException.recoveryExceedsAdvisedLoss("exceeds advised loss"));

        mvc.perform(post("/internal/v1/assumed-claims/{id}/recoveries", CLAIM)
                        .header(BOOK, ASSUMED)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "recoveryType", "SALVAGE",
                                "amount", "500000.00",
                                "currency", "USD"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EIA-RNS-7401"));
    }

    @Test
    void commutationClosesClaim() throws Exception {
        given(commutationService.execute(eq(TENANT), eq(CLAIM), any(), any()))
                .willReturn(commutation());

        mvc.perform(post("/internal/v1/assumed-claims/{id}/commute", CLAIM)
                        .header(BOOK, ASSUMED)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "offerAmount", "800000.00",
                                "acceptedAmount", "750000.00",
                                "currency", "USD",
                                "effectiveDate", "2026-10-01",
                                "approvedBy", APPROVER.toString(),
                                "approverRole", "UNDERWRITER",
                                "releaseDocumentId", DOCUMENT.toString()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.acceptedAmount").value("750000.0000"));
    }

    @Test
    void commutationWithoutApprovalReturns428() throws Exception {
        given(commutationService.execute(eq(TENANT), eq(CLAIM), any(), any()))
                .willThrow(ApiException.commutationApprovalRequired());

        mvc.perform(post("/internal/v1/assumed-claims/{id}/commute", CLAIM)
                        .header(BOOK, ASSUMED)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "offerAmount", "800000.00",
                                "acceptedAmount", "750000.00",
                                "currency", "USD",
                                "effectiveDate", "2026-10-01",
                                "approvedBy", APPROVER.toString(),
                                "approverRole", "ACTUARIAL",
                                "releaseDocumentId", DOCUMENT.toString()))))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("EIA-RNS-7501"));
    }

    @Test
    void missingCommutationReturns404() throws Exception {
        given(commutationService.get(TENANT, CLAIM)).willThrow(ApiException.commutationNotFound());

        mvc.perform(get("/internal/v1/assumed-claims/{id}/commutation", CLAIM)
                        .header(BOOK, ASSUMED)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EIA-RNS-7506"));
    }

    @Test
    void disputeRaiseAndResolve() throws Exception {
        given(disputeService.raise(eq(TENANT), eq(CLAIM), any(), any())).willReturn(dispute(DisputeStatus.OPEN));
        given(disputeService.resolve(eq(TENANT), eq(CLAIM), any(), any(), any()))
                .willReturn(dispute(DisputeStatus.RESOLVED));

        mvc.perform(post("/internal/v1/assumed-claims/{id}/disputes", CLAIM)
                        .header(BOOK, ASSUMED)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "disputeType", "QUANTUM",
                                "reason", "Quantum disputed",
                                "contestedAmount", "100000.00",
                                "currency", "USD"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"));

        mvc.perform(post("/internal/v1/assumed-claims/{id}/disputes/{disputeId}/resolve", CLAIM,
                        UUID.fromString("0192f3a4-dddd-7ddd-8ddd-000000000004"))
                        .header(BOOK, ASSUMED)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "resolution", "Settled by agreement",
                                "status", "RESOLVED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));
    }

    private static LossAdvice advice(AdviceStatus status) {
        Instant now = Instant.parse("2026-10-19T12:00:00Z");
        return new LossAdvice(ADVICE, TENANT, CLAIM, "ADV-001", "CED-778", AdviceType.INITIAL_NOTICE,
                LocalDate.parse("2026-09-03"), LocalDate.parse("2026-09-03"), null, null,
                new BigDecimal("250000.0000"), new BigDecimal("0.0000"), new BigDecimal("250000.0000"),
                null, null, null, "USD", new BigDecimal("0.500000"), null, false, status, null, null,
                null, null, AdviceSource.MANUAL, 1, status == AdviceStatus.POSTED ? now : null,
                null, now, now);
    }

    private static ReserveHistoryEntry reserve(int sequence, String amount, boolean current) {
        return new ReserveHistoryEntry(UUID.randomUUID(), TENANT, CLAIM, ReserveComponent.CASE, sequence,
                new BigDecimal(amount), "USD", LocalDate.parse("2026-10-01"), ReserveReason.REVIEW,
                "Quarterly review", ReserveAuthority.ADJUSTER, false, null, null, current, 1,
                UUID.fromString("00000000-0000-0000-0000-000000000000"),
                Instant.parse("2026-10-19T12:00:00Z"));
    }

    private static BordereauBatch batch(BordereauStatus status, int accepted, int duplicate, int rejected) {
        Instant now = Instant.parse("2026-10-19T12:00:00Z");
        return new BordereauBatch(UUID.randomUUID(), TENANT, CLAIM, "BDX-2026-01", "CED-778",
                LocalDate.parse("2026-09-03"), new BigDecimal("100.0000"), "USD", status,
                AdviceSource.BORDEREAU, 2, accepted + duplicate + rejected, accepted, duplicate, rejected,
                "a".repeat(64), now, now, now);
    }

    private static Commutation commutation() {
        Instant now = Instant.parse("2026-10-19T12:00:00Z");
        return new Commutation(UUID.randomUUID(), TENANT, CLAIM, "COM-2026-000001", CommutationType.FULL,
                new BigDecimal("800000.0000"), new BigDecimal("750000.0000"), "USD",
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-01"), CommutationBasis.NEGOTIATED,
                DOCUMENT, CommutationStatus.EXECUTED, APPROVER, ApproverRole.UNDERWRITER, now, now,
                new BigDecimal("50000.0000"), 1, now, now);
    }

    private static Dispute dispute(DisputeStatus status) {
        Instant now = Instant.parse("2026-10-19T12:00:00Z");
        return new Dispute(UUID.randomUUID(), TENANT, CLAIM, "DSP-2026-000001", DisputeType.QUANTUM,
                "Quantum disputed", new BigDecimal("100000.0000"), "USD", status,
                status == DisputeStatus.RESOLVED ? "Settled" : null, LocalDate.parse("2026-10-01"),
                status == DisputeStatus.RESOLVED ? LocalDate.parse("2026-10-10") : null,
                false, null, 1, now, now);
    }
}
