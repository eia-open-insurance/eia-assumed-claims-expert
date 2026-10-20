package com.eia.assumedclaims.expert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that every Assumed Claims aggregate write and its canonical outbox
 * event commit or roll back together, and asserts the published payload shape
 * for the create, advice, reserve, cash-call, recovery, and commutation events.
 */
@SpringBootTest
@ActiveProfiles("test")
class AssumedClaimsOutboxIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID CONTRACT = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID APPROVER = UUID.fromString("0192f3a4-bbbb-7bbb-8bbb-000000000002");
    private static final UUID RELEASE_DOCUMENT = UUID.fromString("0192f3a4-cccc-7ccc-8ccc-000000000003");
    private static final LocalDate LOSS_DATE = LocalDate.parse("2026-09-01");
    private static final LocalDate REPORT_DATE = LocalDate.parse("2026-09-02");
    private static final LocalDate ADVICE_DATE = LocalDate.parse("2026-09-03");
    private static final LocalDate RESERVE_EFFECTIVE = LocalDate.parse("2026-10-01");

    @Autowired
    private AssumedClaimService claimService;

    @Autowired
    private LossAdviceService adviceService;

    @Autowired
    private ReserveService reserveService;

    @Autowired
    private CashCallService cashCallService;

    @Autowired
    private RecoveryService recoveryService;

    @Autowired
    private CommutationService commutationService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void clean() {
        transaction = new TransactionTemplate(transactionManager);
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM assumed_claim_audit");
        jdbc.update("DELETE FROM assumed_commutation");
        jdbc.update("DELETE FROM assumed_recovery");
        jdbc.update("DELETE FROM assumed_cash_call");
        jdbc.update("DELETE FROM assumed_reserve_history");
        jdbc.update("DELETE FROM assumed_loss_advice");
        jdbc.update("DELETE FROM assumed_dispute");
        jdbc.update("DELETE FROM assumed_bordereau");
        jdbc.update("DELETE FROM assumed_claim");
    }

    @Test
    void createWritesTheClaimAndRegisteredEventTogether() {
        AssumedClaim claim = claimService.create(createRequest("100000.00"), "adjuster");

        Map<String, Object> event = event(claim.id(), "assumed.claim.registered");

        assertThat(event.get("event_type")).isEqualTo("assumed.claim.registered");
        assertThat(event.get("aggregate_type")).isEqualTo("AssumedClaim");
        assertThat(event.get("aggregate_version")).isEqualTo(1L);
        assertThat(event.get("event_version")).isEqualTo(1);
        assertThat(event.get("status")).isEqualTo("PENDING");
        assertThat(event.get("tenant_id")).isEqualTo(TENANT);
        assertThat(event.get("partition_key")).isEqualTo(claim.id().toString());

        String payload = (String) event.get("payload");
        assertThat(payload)
                .contains("\"assumedClaimId\":\"" + claim.id() + "\"")
                .contains("\"assumedClaimNumber\":\"" + claim.assumedClaimNumber() + "\"")
                .contains("\"cedentClaimNumber\":\"CED-OBX-1\"")
                .contains("\"contractId\":\"" + CONTRACT + "\"")
                .contains("\"businessBook\":\"ASSUMED\"")
                .contains("\"status\":\"NOTIFIED\"")
                .contains("\"version\":0")
                .contains("\"lossDate\":\"2026-09-01\"")
                .contains("\"reportDate\":\"2026-09-02\"")
                .contains("\"reserveAmount\":\"100000.0000\"")
                .contains("\"reserveCurrency\":\"USD\"");

        String headers = (String) event.get("headers");
        assertThat(headers)
                .contains("\"book\":\"ASSUMED\"")
                .contains("\"vertical\":\"assumed\"")
                .contains("\"businessKey\":\"" + claim.assumedClaimNumber() + "\"")
                .contains("\"schemaVersion\":\"1.0.0\"");
    }

    @Test
    void createRollbackLeavesNeitherTheClaimNorTheEvent() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            claimService.create(createRequest("100000.00"), "adjuster");
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(count("assumed_claim")).isZero();
        assertThat(count("assumed_reserve_history")).isZero();
        assertThat(count("assumed_claim_audit")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void acceptingAnAdviceWritesThePostedEvent() {
        AssumedClaim claim = claimService.create(createRequest("100000.00"), "adjuster");
        LossAdvice advice = adviceService.record(TENANT, claim.id(), adviceRequest(), "adjuster");

        LossAdvice posted = adviceService.accept(TENANT, claim.id(), advice.id(), null, "adjuster");
        assertThat(posted.status()).isEqualTo(AdviceStatus.POSTED);

        Map<String, Object> event = event(claim.id(), "assumed.claim.loss-advice-posted");
        assertThat(event.get("aggregate_version")).isEqualTo(3L);

        String payload = (String) event.get("payload");
        assertThat(payload)
                .contains("\"assumedClaimId\":\"" + claim.id() + "\"")
                .contains("\"assumedClaimNumber\":\"" + claim.assumedClaimNumber() + "\"")
                .contains("\"cedentClaimNumber\":\"CED-OBX-1\"")
                .contains("\"contractId\":\"" + CONTRACT + "\"")
                .contains("\"status\":\"NOTIFIED\"")
                .contains("\"version\":2")
                .contains("\"adviceId\":\"" + advice.id() + "\"")
                .contains("\"adviceNumber\":\"ADV-OBX-1\"")
                .contains("\"adviceType\":\"INITIAL_NOTICE\"")
                .contains("\"adviceStatus\":\"POSTED\"")
                .contains("\"reportedAmount\":\"250000.0000\"")
                .contains("\"outstandingAmount\":\"250000.0000\"")
                .contains("\"cessionPct\":\"0.500000\"")
                .contains("\"currency\":\"USD\"");
    }

    @Test
    void statusChangeWritesUpdatedThenClosedEvents() {
        AssumedClaim claim = claimService.create(createRequest("0.00"), "adjuster");
        LossAdvice advice = adviceService.record(TENANT, claim.id(), adviceRequest(), "adjuster");
        adviceService.accept(TENANT, claim.id(), advice.id(), null, "adjuster");

        AssumedClaim notified = claimService.get(TENANT, claim.id());
        AssumedClaim opened = claimService.changeStatus(
                TENANT, claim.id(), AssumedClaimStatus.OPEN, notified.version(), "adjuster");
        assertThat(opened.status()).isEqualTo(AssumedClaimStatus.OPEN);

        Map<String, Object> updated = event(claim.id(), "assumed.claim.updated");
        assertThat(updated.get("aggregate_version")).isEqualTo(4L);
        assertThat((String) updated.get("payload"))
                .contains("\"changedFields\":[\"status\"]")
                .contains("\"from\":\"NOTIFIED\"")
                .contains("\"to\":\"OPEN\"")
                .contains("\"status\":\"OPEN\"")
                .contains("\"version\":3");

        AssumedClaim denied = claimService.changeStatus(
                TENANT, claim.id(), AssumedClaimStatus.DENIED, opened.version(), "adjuster");
        AssumedClaim closedClaim = claimService.close(TENANT, claim.id(), "adjuster");

        Map<String, Object> closed = event(claim.id(), "assumed.claim.closed");
        assertThat(closed.get("aggregate_version")).isEqualTo(6L);
        assertThat((String) closed.get("payload"))
                .contains("\"from\":\"DENIED\"")
                .contains("\"to\":\"CLOSED\"")
                .contains("\"status\":\"CLOSED\"")
                .contains("\"version\":5");
        assertThat(denied.status()).isEqualTo(AssumedClaimStatus.DENIED);

        AssumedClaim reopened = claimService.changeStatus(
                TENANT, claim.id(), AssumedClaimStatus.REOPENED, closedClaim.version(), "adjuster");
        assertThat(reopened.status()).isEqualTo(AssumedClaimStatus.REOPENED);

        Map<String, Object> reopenedEvent = event(claim.id(), "assumed.claim.reopened");
        assertThat(reopenedEvent.get("aggregate_version")).isEqualTo(7L);
        assertThat((String) reopenedEvent.get("payload"))
                .contains("\"from\":\"CLOSED\"")
                .contains("\"to\":\"REOPENED\"")
                .contains("\"status\":\"REOPENED\"")
                .contains("\"version\":6");
    }

    @Test
    void reserveChangeWritesTheReserveChangedEventAndRollsBackWithTheClaim() {
        AssumedClaim claim = openClaim();
        ReserveHistoryEntry entry = reserveService.change(
                TENANT, claim.id(), reserveRequest(), null, "adjuster");

        assertThat(entry.component()).isEqualTo(ReserveComponent.CASE);
        assertThat(entry.sequence()).isEqualTo(2);

        Map<String, Object> event = event(claim.id(), "assumed.claim.reserve-changed");
        assertThat(event.get("aggregate_version")).isEqualTo(5L);
        assertThat((String) event.get("payload"))
                .contains("\"assumedClaimId\":\"" + claim.id() + "\"")
                .contains("\"reserveId\":\"" + entry.id() + "\"")
                .contains("\"component\":\"CASE\"")
                .contains("\"sequence\":2")
                .contains("\"amount\":\"150000.0000\"")
                .contains("\"previousReserveAmount\":\"100000.0000\"")
                .contains("\"reserveAmount\":\"150000.0000\"")
                .contains("\"currency\":\"USD\"")
                .contains("\"effectiveDate\":\"2026-10-01\"")
                .contains("\"reasonCode\":\"DEVELOPMENT\"")
                .contains("\"authority\":\"ADJUSTER\"")
                .contains("\"status\":\"OPEN\"")
                .contains("\"version\":4");

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            reserveService.change(TENANT, claim.id(),
                    new ReserveChangeRequest(ReserveComponent.CASE, new BigDecimal("5000.00"), "USD",
                            LocalDate.parse("2026-10-02"), ReserveReason.REVIEW, "second movement",
                            ReserveAuthority.ADJUSTER, null),
                    null, "adjuster");
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(eventCount(claim.id(), "assumed.claim.reserve-changed")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT reserve_amount FROM assumed_claim WHERE id = ?", BigDecimal.class, claim.id()))
                .isEqualByComparingTo("150000.0000");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM assumed_reserve_history
                 WHERE assumed_claim_id = ? AND is_current = TRUE
                """, Integer.class, claim.id())).isEqualTo(1);
    }

    @Test
    void cashCallWritesTheCashCallRequestedEvent() {
        AssumedClaim claim = openClaim();
        CashCall call = cashCallService.create(TENANT, claim.id(), cashCallRequest(), "adjuster");

        assertThat(call.status()).isEqualTo(CashCallStatus.SUBMITTED);

        Map<String, Object> event = event(claim.id(), "assumed.claim.cash-call-requested");
        assertThat(event.get("aggregate_version")).isEqualTo(5L);
        assertThat((String) event.get("payload"))
                .contains("\"assumedClaimId\":\"" + claim.id() + "\"")
                .contains("\"cashCallId\":\"" + call.id() + "\"")
                .contains("\"requestNumber\":\"" + call.requestNumber() + "\"")
                .contains("\"requestedAmount\":\"50000.0000\"")
                .contains("\"currency\":\"USD\"")
                .contains("\"requestedAt\":\"2026-10-01\"")
                .contains("\"dueDate\":\"2026-10-15\"")
                .contains("\"cashCallStatus\":\"SUBMITTED\"")
                .contains("\"reason\":\"LARGE_LOSS\"")
                .contains("\"approvalStatus\":\"NOT_REQUIRED\"")
                .contains("\"status\":\"OPEN\"")
                .contains("\"version\":4");
    }

    @Test
    void recoveryWritesTheRecoveryRecordedEvent() {
        AssumedClaim claim = openClaim();
        Recovery recovery = recoveryService.create(TENANT, claim.id(), recoveryRequest(), "adjuster");

        Map<String, Object> event = event(claim.id(), "assumed.claim.recovery-recorded");
        assertThat(event.get("aggregate_version")).isEqualTo(5L);
        assertThat((String) event.get("payload"))
                .contains("\"assumedClaimId\":\"" + claim.id() + "\"")
                .contains("\"recoveryId\":\"" + recovery.id() + "\"")
                .contains("\"recoveryType\":\"SALVAGE\"")
                .contains("\"amount\":\"25000.0000\"")
                .contains("\"currency\":\"USD\"")
                .contains("\"receivedDate\":\"2026-10-01\"")
                .contains("\"recoveryStatus\":\"RECEIVED\"")
                .contains("\"expectedAmount\":\"30000.0000\"")
                .contains("\"status\":\"OPEN\"")
                .contains("\"version\":4");
    }

    @Test
    void commutationWritesTheCommutedEventAndClosesTheClaim() {
        AssumedClaim claim = openClaim();
        Commutation commutation = commutationService.execute(
                TENANT, claim.id(), commuteRequest(), "underwriter");

        assertThat(commutation.status()).isEqualTo(CommutationStatus.EXECUTED);

        Map<String, Object> event = event(claim.id(), "assumed.claim.commuted");
        assertThat(event.get("aggregate_version")).isEqualTo(5L);
        assertThat((String) event.get("payload"))
                .contains("\"assumedClaimId\":\"" + claim.id() + "\"")
                .contains("\"commutationId\":\"" + commutation.id() + "\"")
                .contains("\"commutationNumber\":\"" + commutation.commutationNumber() + "\"")
                .contains("\"commutationType\":\"FULL\"")
                .contains("\"offerAmount\":\"70000.0000\"")
                .contains("\"acceptedAmount\":\"60000.0000\"")
                .contains("\"savingsAmount\":\"10000.0000\"")
                .contains("\"releasedReserveAmount\":\"100000.0000\"")
                .contains("\"reserveAmount\":\"0.0000\"")
                .contains("\"releaseDocumentId\":\"" + RELEASE_DOCUMENT + "\"")
                .contains("\"commutationStatus\":\"EXECUTED\"")
                .contains("\"status\":\"CLOSED\"")
                .contains("\"version\":4");

        AssumedClaim closed = claimService.get(TENANT, claim.id());
        assertThat(closed.status()).isEqualTo(AssumedClaimStatus.CLOSED);
        assertThat(closed.version()).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM assumed_reserve_history
                 WHERE assumed_claim_id = ? AND is_current = TRUE
                """, Integer.class, claim.id())).isZero();
    }

    private AssumedClaim openClaim() {
        AssumedClaim claim = claimService.create(createRequest("100000.00"), "adjuster");
        LossAdvice advice = adviceService.record(TENANT, claim.id(), adviceRequest(), "adjuster");
        adviceService.accept(TENANT, claim.id(), advice.id(), null, "adjuster");
        AssumedClaim notified = claimService.get(TENANT, claim.id());
        return claimService.changeStatus(
                TENANT, claim.id(), AssumedClaimStatus.OPEN, notified.version(), "adjuster");
    }

    private CreateAssumedClaimRequest createRequest(String reserveAmount) {
        return new CreateAssumedClaimRequest(
                TENANT, CONTRACT, "CED-OBX-1", LOSS_DATE, REPORT_DATE,
                new BigDecimal(reserveAmount), "USD");
    }

    private RecordAdviceRequest adviceRequest() {
        return new RecordAdviceRequest(
                "ADV-OBX-1",
                AdviceType.INITIAL_NOTICE,
                ADVICE_DATE,
                ADVICE_DATE,
                null,
                null,
                new BigDecimal("250000.00"),
                null,
                new BigDecimal("250000.00"),
                null,
                null,
                null,
                "USD",
                new BigDecimal("0.500000"),
                null,
                null,
                null,
                "CED-OBX-1",
                null);
    }

    private ReserveChangeRequest reserveRequest() {
        return new ReserveChangeRequest(
                ReserveComponent.CASE,
                new BigDecimal("150000.00"),
                "USD",
                RESERVE_EFFECTIVE,
                ReserveReason.DEVELOPMENT,
                "Development on the cedent advice",
                ReserveAuthority.ADJUSTER,
                null);
    }

    private RequestCashCallRequest cashCallRequest() {
        return new RequestCashCallRequest(
                new BigDecimal("50000.00"),
                "USD",
                CashCallReason.LARGE_LOSS,
                "Interim cash funding",
                LocalDate.parse("2026-10-01"),
                LocalDate.parse("2026-10-15"),
                null,
                null,
                null);
    }

    private RecordRecoveryRequest recoveryRequest() {
        return new RecordRecoveryRequest(
                RecoveryType.SALVAGE,
                new BigDecimal("25000.00"),
                "USD",
                LocalDate.parse("2026-10-01"),
                RecoveryStatus.RECEIVED,
                null,
                null,
                new BigDecimal("30000.00"),
                null);
    }

    private CommuteClaimRequest commuteRequest() {
        return new CommuteClaimRequest(
                new BigDecimal("70000.00"),
                new BigDecimal("60000.00"),
                "USD",
                LocalDate.parse("2026-10-05"),
                null,
                CommutationType.FULL,
                CommutationBasis.NEGOTIATED,
                APPROVER,
                ApproverRole.UNDERWRITER,
                RELEASE_DOCUMENT,
                null);
    }

    private Map<String, Object> event(UUID claimId, String eventType) {
        return jdbc.queryForMap("""
                SELECT event_type, aggregate_type, aggregate_version, event_version, status,
                       tenant_id, payload, headers, partition_key
                  FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = ?
                """, claimId, eventType);
    }

    private int eventCount(UUID claimId, String eventType) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = ?
                """, Integer.class, claimId, eventType);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
