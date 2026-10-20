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
 * Proves that claim assignment persists the four new columns, writes
 * {@code assumed.claim.assigned} to the outbox in the same transaction, and
 * rolls both back together. Also covers the assignment list/search filter.
 */
@SpringBootTest
@ActiveProfiles("test")
class AssumedClaimAssignmentOutboxIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID CONTRACT = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final String ASSIGNED = "assumed.claim.assigned";

    @Autowired
    private AssumedClaimService claimService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void clean() {
        transaction = new TransactionTemplate(transactionManager);
        jdbc.update("DELETE FROM claim_command_idempotency");
        jdbc.update("DELETE FROM claim_task");
        jdbc.update("DELETE FROM claim_note");
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
    void assignPersistsTheColumnsAndWritesTheEventAndAudit() {
        AssumedClaim claim = createClaim("CED-ASG-1");
        assertThat(claim.assignedAdjuster()).isNull();
        assertThat(claim.assignedTeam()).isNull();
        assertThat(claim.assignedAt()).isNull();

        AssumedClaim assigned = claimService.assign(TENANT, claim.id(),
                new ClaimAssignmentRequest("A. Adjuster", "CASUALTY", 0), "adjuster@example.com");

        assertThat(assigned.assignedAdjuster()).isEqualTo("A. Adjuster");
        assertThat(assigned.assignedTeam()).isEqualTo("CASUALTY");
        assertThat(assigned.assignedAt()).isNotNull();
        assertThat(assigned.version()).isEqualTo(1);

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT assigned_adjuster, assigned_team, assigned_at, assigned_by, version
                  FROM assumed_claim
                 WHERE tenant_id = ? AND id = ?
                """, TENANT, claim.id());
        assertThat(row.get("assigned_adjuster")).isEqualTo("A. Adjuster");
        assertThat(row.get("assigned_team")).isEqualTo("CASUALTY");
        assertThat(row.get("assigned_at")).isNotNull();
        assertThat(row.get("assigned_by")).isEqualTo("adjuster@example.com");
        assertThat(((Number) row.get("version")).intValue()).isEqualTo(1);

        Map<String, Object> event = event(claim.id(), ASSIGNED);
        assertThat(event.get("aggregate_type")).isEqualTo("AssumedClaim");
        assertThat(event.get("aggregate_version")).isEqualTo(2L);
        assertThat(event.get("event_version")).isEqualTo(1);
        assertThat(event.get("status")).isEqualTo("PENDING");
        assertThat(event.get("tenant_id")).isEqualTo(TENANT);
        assertThat(event.get("partition_key")).isEqualTo(claim.id().toString());

        String payload = (String) event.get("payload");
        assertThat(payload)
                .contains("\"assumedClaimId\":\"" + claim.id() + "\"")
                .contains("\"assumedClaimNumber\":\"" + claim.assumedClaimNumber() + "\"")
                .contains("\"businessBook\":\"ASSUMED\"")
                .contains("\"assignedAdjuster\":\"A. Adjuster\"")
                .contains("\"assignedTeam\":\"CASUALTY\"")
                .contains("\"assignedBy\":\"adjuster@example.com\"")
                .contains("\"assignedAt\":")
                .contains("\"changedFields\":[\"assignedAdjuster\",\"assignedTeam\"]")
                .contains("\"status\":\"NOTIFIED\"")
                .contains("\"version\":1")
                .doesNotContain("previousAssignedAdjuster");

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM assumed_claim_audit
                 WHERE tenant_id = ? AND assumed_claim_id = ? AND event_type = 'CLAIM_ASSIGNED'
                """, Integer.class, TENANT, claim.id())).isEqualTo(1);
    }

    @Test
    void assignmentWithoutATeamOrActorUsesTheAdjusterAsTheActor() {
        AssumedClaim claim = createClaim("CED-ASG-1B");

        AssumedClaim assigned = claimService.assign(TENANT, claim.id(),
                new ClaimAssignmentRequest("A. Adjuster", null, 0), null);

        assertThat(assigned.assignedAdjuster()).isEqualTo("A. Adjuster");
        assertThat(assigned.assignedTeam()).isNull();

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT assigned_team, assigned_by FROM assumed_claim WHERE id = ?
                """, claim.id());
        assertThat(row.get("assigned_team")).isNull();
        assertThat(row.get("assigned_by")).isEqualTo("A. Adjuster");

        String payload = (String) event(claim.id(), ASSIGNED).get("payload");
        assertThat(payload)
                .contains("\"assignedAdjuster\":\"A. Adjuster\"")
                .contains("\"assignedBy\":\"A. Adjuster\"")
                .doesNotContain("\"assignedTeam\"");
    }

    @Test
    void reassignUpdatesTheOwnerAndReportsThePreviousOne() {
        AssumedClaim claim = createClaim("CED-ASG-2");
        AssumedClaim first = claimService.assign(TENANT, claim.id(),
                new ClaimAssignmentRequest("A. Adjuster", "CASUALTY", 0), "adjuster@example.com");
        AssumedClaim second = claimService.assign(TENANT, claim.id(),
                new ClaimAssignmentRequest("B. Examiner", "PROPERTY", first.version()), "lead@example.com");

        assertThat(second.assignedAdjuster()).isEqualTo("B. Examiner");
        assertThat(second.assignedTeam()).isEqualTo("PROPERTY");
        assertThat(second.version()).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT assigned_by FROM assumed_claim WHERE id = ?
                """, String.class, claim.id())).isEqualTo("lead@example.com");

        assertThat(eventCount(claim.id(), ASSIGNED)).isEqualTo(2);
        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT aggregate_version, payload FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = ?
                 ORDER BY aggregate_version
                """, claim.id(), ASSIGNED);
        assertThat(events.get(1).get("aggregate_version")).isEqualTo(3L);
        String payload = (String) events.get(1).get("payload");
        assertThat(payload)
                .contains("\"assignedAdjuster\":\"B. Examiner\"")
                .contains("\"assignedTeam\":\"PROPERTY\"")
                .contains("\"previousAssignedAdjuster\":\"A. Adjuster\"")
                .contains("\"previousAssignedTeam\":\"CASUALTY\"")
                .contains("\"version\":2");
    }

    @Test
    void aStaleAssignmentWritesNothing() {
        AssumedClaim claim = createClaim("CED-ASG-3");
        claimService.assign(TENANT, claim.id(),
                new ClaimAssignmentRequest("A. Adjuster", "CASUALTY", 0), "adjuster@example.com");

        assertThatThrownBy(() -> claimService.assign(TENANT, claim.id(),
                new ClaimAssignmentRequest("B. Examiner", "PROPERTY", 0), "lead@example.com"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expected version 0");

        AssumedClaim stored = claimService.get(TENANT, claim.id());
        assertThat(stored.assignedAdjuster()).isEqualTo("A. Adjuster");
        assertThat(stored.assignedTeam()).isEqualTo("CASUALTY");
        assertThat(stored.version()).isEqualTo(1);
        assertThat(eventCount(claim.id(), ASSIGNED)).isEqualTo(1);
    }

    @Test
    void repeatingTheSameAssignmentWritesNoEventAndKeepsTheVersion() {
        AssumedClaim claim = createClaim("CED-ASG-4");
        AssumedClaim assigned = claimService.assign(TENANT, claim.id(),
                new ClaimAssignmentRequest("A. Adjuster", "CASUALTY", 0), "adjuster@example.com");

        AssumedClaim noOp = claimService.assign(TENANT, claim.id(),
                new ClaimAssignmentRequest("A. Adjuster", "CASUALTY", assigned.version()),
                "another@example.com");

        assertThat(noOp.version()).isEqualTo(assigned.version());
        assertThat(noOp.assignedAt()).isEqualTo(assigned.assignedAt());
        assertThat(eventCount(claim.id(), ASSIGNED)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM assumed_claim_audit
                 WHERE tenant_id = ? AND assumed_claim_id = ? AND event_type = 'CLAIM_ASSIGNED'
                """, Integer.class, TENANT, claim.id())).isEqualTo(1);
    }

    @Test
    void rollbackLeavesNeitherTheAssignmentNorTheEvent() {
        AssumedClaim claim = createClaim("CED-ASG-5");

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            claimService.assign(TENANT, claim.id(),
                    new ClaimAssignmentRequest("A. Adjuster", "CASUALTY", 0), "adjuster@example.com");
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        AssumedClaim stored = claimService.get(TENANT, claim.id());
        assertThat(stored.assignedAdjuster()).isNull();
        assertThat(stored.assignedTeam()).isNull();
        assertThat(stored.assignedAt()).isNull();
        assertThat(stored.version()).isZero();
        assertThat(eventCount(claim.id(), ASSIGNED)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM assumed_claim_audit
                 WHERE tenant_id = ? AND assumed_claim_id = ? AND event_type = 'CLAIM_ASSIGNED'
                """, Integer.class, TENANT, claim.id())).isZero();
    }

    @Test
    void listReturnsTheAssignmentAndFiltersByAdjusterOrTeam() {
        AssumedClaim first = createClaim("CED-ASG-6");
        AssumedClaim second = createClaim("CED-ASG-7");
        claimService.assign(TENANT, first.id(),
                new ClaimAssignmentRequest("A. Adjuster", "CASUALTY", 0), "adjuster@example.com");
        claimService.assign(TENANT, second.id(),
                new ClaimAssignmentRequest("B. Examiner", "PROPERTY", 0), "lead@example.com");

        List<AssumedClaim> all = claimService.list(TENANT);
        assertThat(all).hasSize(2);
        assertThat(all).allSatisfy(claim -> {
            assertThat(claim.assignedAdjuster()).isNotNull();
            assertThat(claim.assignedTeam()).isNotNull();
            assertThat(claim.assignedAt()).isNotNull();
        });

        List<AssumedClaim> casualty = claimService.list(TENANT, "A. Adjuster", "CASUALTY");
        assertThat(casualty).extracting(AssumedClaim::id).containsExactly(first.id());
        assertThat(casualty.get(0).assignedAdjuster()).isEqualTo("A. Adjuster");
        assertThat(casualty.get(0).assignedTeam()).isEqualTo("CASUALTY");

        List<AssumedClaim> property = claimService.list(TENANT, null, "PROPERTY");
        assertThat(property).extracting(AssumedClaim::id).containsExactly(second.id());

        assertThat(claimService.list(TENANT, "A. Adjuster", "PROPERTY")).isEmpty();
        assertThat(claimService.list(OTHER_TENANT, null, null)).isEmpty();
    }

    private AssumedClaim createClaim(String cedentClaimNumber) {
        return claimService.create(new CreateAssumedClaimRequest(
                TENANT, CONTRACT, cedentClaimNumber, LocalDate.parse("2026-09-01"),
                LocalDate.parse("2026-09-02"), new BigDecimal("100000.00"), "USD"), "adjuster");
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
}
