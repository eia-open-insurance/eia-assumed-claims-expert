package com.eia.assumedclaims.expert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live PostgreSQL smoke test. It is excluded from the normal test task and runs only with
 * {@code EIA_LIVE_POSTGRES=true}; it creates a dedicated {@code eia_assumed_live} database.
 *
 * <p>Flow: advice -&gt; reserve -&gt; cash call -&gt; recovery, with the opening gate, recovery cap,
 * append-only audit, dispute blocking, and commutation closure exercised against real SQL.</p>
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "EIA_LIVE_POSTGRES", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AssumedClaimsLivePostgresTest {
    private static final String DATABASE = "eia_assumed_live";
    private static final String ADMIN_URL = env("EIA_LIVE_ADMIN_URL", "jdbc:postgresql://localhost:5432/postgres");
    private static final String DB_URL = env("EIA_LIVE_DB_URL", "jdbc:postgresql://localhost:5432/" + DATABASE);
    private static final String USER = env("EIA_LIVE_DB_USER", "postgres");
    private static final String PASSWORD = env("EIA_LIVE_DB_PASSWORD", "postgres");
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID CONTRACT = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final String ASSUMED = "ASSUMED";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        ensureDatabase();
        registry.add("spring.datasource.url", () -> DB_URL);
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.sql.init.mode", () -> "always");
    }

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE assumed_claim CASCADE");
    }

    @Test
    void adviceReserveCashCallRecoveryAndCommutationFlow() throws Exception {
        JsonNode claim = ok(post("/internal/v1/assumed-claims", Map.of(
                "tenantId", TENANT.toString(),
                "contractId", CONTRACT.toString(),
                "cedentClaimNumber", "CED-LIVE-1",
                "lossDate", LocalDate.now().minusDays(30).toString(),
                "reportDate", LocalDate.now().minusDays(29).toString(),
                "reserveAmount", "0.00",
                "reserveCurrency", "USD")), 201);
        String claimId = claim.get("id").asText();
        assertEquals("NOTIFIED", claim.get("status").asText());

        JsonNode openGate = error(post("/internal/v1/assumed-claims/" + claimId + "/status", Map.of(
                "tenantId", TENANT.toString(),
                "targetStatus", "OPEN",
                "expectedVersion", claim.get("version").asInt())), 422);        assertEquals("EIA-RNS-7102", openGate.get("code").asText());

        JsonNode advice = ok(post("/internal/v1/assumed-claims/" + claimId + "/advices", Map.of(
                "adviceNumber", "ADV-LIVE-1",
                "adviceType", "INITIAL_NOTICE",
                "adviceDate", LocalDate.now().minusDays(20).toString(),
                "reportedAmount", "250000.00",
                "paidAmount", "0.00",
                "outstandingAmount", "250000.00",
                "currency", "USD",
                "cessionPct", "0.500000")), 201);
        assertEquals("RECEIVED", advice.get("status").asText());
        assertEquals("250000.0000", advice.get("reportedAmount").asText());
        String adviceId = advice.get("id").asText();

        JsonNode accepted = ok(post("/internal/v1/assumed-claims/" + claimId + "/advices/" + adviceId + "/accept", null), 200);
        assertEquals("POSTED", accepted.get("status").asText());

        JsonNode current = ok(get("/internal/v1/assumed-claims/" + claimId + "?tenantId=" + TENANT), 200);
        JsonNode opened = ok(post("/internal/v1/assumed-claims/" + claimId + "/status", Map.of(
                "tenantId", TENANT.toString(),
                "targetStatus", "OPEN",
                "expectedVersion", current.get("version").asInt())), 200);
        assertEquals("OPEN", opened.get("status").asText());

        JsonNode reserve = ok(post("/internal/v1/assumed-claims/" + claimId + "/reserves", Map.of(
                "component", "CASE",
                "amount", "100000.00",
                "currency", "USD",
                "effectiveDate", LocalDate.now().minusDays(2).toString(),
                "reasonCode", "INITIAL",
                "authority", "ADJUSTER")), 201);
        assertEquals("100000.0000", reserve.get("amount").asText());
        assertTrue(reserve.get("isCurrent").asBoolean());

        JsonNode reserves = ok(get("/internal/v1/assumed-claims/" + claimId + "/reserves"), 200);
        assertEquals(2, reserves.size(), "initial zero reserve plus the CASE reserve");
        JsonNode financials = ok(get("/internal/v1/assumed-claims/" + claimId + "/financials"), 200);
        assertEquals("100000.0000", financials.get("cededReserveAmount").asText());
        assertEquals("225000.0000", financials.get("cededIncurredAmount").asText());

        JsonNode cash = ok(post("/internal/v1/assumed-claims/" + claimId + "/cash-calls", Map.of(
                "requestedAmount", "50000.00",
                "currency", "USD",
                "reason", "CASH_LOSS",
                "dueDate", LocalDate.now().plusDays(10).toString())), 201);
        assertEquals("SUBMITTED", cash.get("status").asText());

        JsonNode cashExcess = error(post("/internal/v1/assumed-claims/" + claimId + "/cash-calls", Map.of(
                "requestedAmount", "60000.00",
                "currency", "USD",
                "reason", "CASH_LOSS")), 422);
        assertEquals("EIA-RNS-7301", cashExcess.get("code").asText());

        JsonNode recovery = ok(post("/internal/v1/assumed-claims/" + claimId + "/recoveries", Map.of(
                "recoveryType", "SALVAGE",
                "amount", "25000.00",
                "currency", "USD",
                "receivedDate", LocalDate.now().toString(),
                "status", "RECEIVED")), 201);
        assertEquals("25000.0000", recovery.get("amount").asText());

        JsonNode recoveryExcess = error(post("/internal/v1/assumed-claims/" + claimId + "/recoveries", Map.of(
                "recoveryType", "SUBROGATION",
                "amount", "200000.00",
                "currency", "USD")), 422);
        assertEquals("EIA-RNS-7401", recoveryExcess.get("code").asText());

        JsonNode updatedFinancials = ok(get("/internal/v1/assumed-claims/" + claimId + "/financials"), 200);
        assertEquals("25000.0000", updatedFinancials.get("cededRecoveryAmount").asText());
        assertEquals("200000.0000", updatedFinancials.get("netCededAmount").asText());

        JsonNode audit = ok(get("/internal/v1/assumed-claims/" + claimId + "/audit"), 200);
        List<String> events = new java.util.ArrayList<>();
        audit.forEach(entry -> events.add(entry.get("eventType").asText()));
        assertTrue(events.contains("CLAIM_REGISTERED"));
        assertTrue(events.contains("LOSS_ADVICE_RECORDED"));
        assertTrue(events.contains("LOSS_ADVICE_ACCEPTED"));
        assertTrue(events.contains("RESERVE_CHANGED"));
        assertTrue(events.contains("CASH_CALL_REQUESTED"));
        assertTrue(events.contains("RECOVERY_RECORDED"));

        assertThrows(DataAccessException.class,
                () -> jdbc.update("UPDATE assumed_claim_audit SET actor = 'tamper'"),
                "the audit table must be append-only (RNS audit)");
        assertThrows(DataAccessException.class,
                () -> jdbc.update("DELETE FROM assumed_claim_audit"));

        JsonNode dispute = ok(post("/internal/v1/assumed-claims/" + claimId + "/disputes", Map.of(
                "disputeType", "QUANTUM",
                "reason", "Cedent quantum is disputed",
                "contestedAmount", "25000.00",
                "currency", "USD")), 201);
        JsonNode blocked = error(post("/internal/v1/assumed-claims/" + claimId + "/commute", Map.of(
                "offerAmount", "70000.00",
                "acceptedAmount", "60000.00",
                "currency", "USD",
                "effectiveDate", LocalDate.now().toString(),
                "approvedBy", UUID.randomUUID().toString(),
                "approverRole", "UNDERWRITER",
                "releaseDocumentId", UUID.randomUUID().toString())), 422);
        assertEquals("EIA-RNS-7502", blocked.get("code").asText());

        ok(post("/internal/v1/assumed-claims/" + claimId + "/disputes/" + dispute.get("id").asText() + "/resolve",
                Map.of("resolution", "Resolved by agreement", "status", "RESOLVED")), 200);

        JsonNode commutation = ok(post("/internal/v1/assumed-claims/" + claimId + "/commute", Map.of(
                "offerAmount", "70000.00",
                "acceptedAmount", "60000.00",
                "currency", "USD",
                "effectiveDate", LocalDate.now().toString(),
                "approvedBy", UUID.randomUUID().toString(),
                "approverRole", "UNDERWRITER",
                "releaseDocumentId", UUID.randomUUID().toString())), 201);
        assertEquals("EXECUTED", commutation.get("status").asText());

        JsonNode closed = ok(get("/internal/v1/assumed-claims/" + claimId + "?tenantId=" + TENANT), 200);
        assertEquals("CLOSED", closed.get("status").asText());
        JsonNode closedFinancials = ok(get("/internal/v1/assumed-claims/" + claimId + "/financials"), 200);
        assertEquals("0.0000", closedFinancials.get("cededReserveAmount").asText());
    }

    @Test
    void bordereauIngestDetectsDuplicatesAndReplaysByReference() throws Exception {
        JsonNode claim = ok(post("/internal/v1/assumed-claims", Map.of(
                "tenantId", TENANT.toString(),
                "contractId", CONTRACT.toString(),
                "cedentClaimNumber", "CED-LIVE-2",
                "lossDate", LocalDate.now().minusDays(10).toString(),
                "reserveAmount", "0.00",
                "reserveCurrency", "USD")), 201);
        String claimId = claim.get("id").asText();
        ok(post("/internal/v1/assumed-claims/" + claimId + "/status", Map.of(
                "tenantId", TENANT.toString(),
                "targetStatus", "CLOSED", "expectedVersion", claim.get("version").asInt())), 409);

        Map<String, Object> body = Map.of(
                "bordereauReference", "BDX-LIVE-1",
                "adviceDate", LocalDate.now().minusDays(5).toString(),
                "currency", "USD",
                "lines", List.of(
                        Map.of("adviceNumber", "BDX-LINE-1", "reportedAmount", "10000.00"),
                        Map.of("adviceNumber", "BDX-LINE-2", "reportedAmount", "20000.00"),
                        Map.of("adviceNumber", "BDX-LINE-1", "reportedAmount", "30000.00")));
        JsonNode first = ok(post("/internal/v1/assumed-claims/" + claimId + "/bordereaux", body), 201);
        assertEquals("PARTIAL", first.get("batch").get("status").asText());
        assertEquals(2, first.get("batch").get("acceptedCount").asInt());
        assertEquals(1, first.get("batch").get("duplicateCount").asInt());
        assertFalse(first.get("replayed").asBoolean());

        JsonNode replay = ok(post("/internal/v1/assumed-claims/" + claimId + "/bordereaux", body), 200);
        assertTrue(replay.get("replayed").asBoolean());
        assertEquals(first.get("batch").get("id").asText(), replay.get("batch").get("id").asText());
    }

    private ResponseEntity<JsonNode> get(String path) {
        return rest.exchange(path, HttpMethod.GET, entity(null), JsonNode.class);
    }

    private ResponseEntity<JsonNode> post(String path, Object body) {
        return rest.exchange(path, HttpMethod.POST, entity(body), JsonNode.class);
    }

    private HttpEntity<Object> entity(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("X-EIA-Book", ASSUMED);
        headers.set("X-EIA-Tenant", TENANT.toString());
        headers.set("X-EIA-User", "live-smoke@example.com");
        return new HttpEntity<>(body, headers);
    }

    private JsonNode ok(ResponseEntity<JsonNode> response, int expectedStatus) {
        assertEquals(expectedStatus, response.getStatusCode().value(),
                () -> "unexpected response: " + response.getBody());
        assertNotNull(response.getBody());
        return response.getBody();
    }

    private JsonNode error(ResponseEntity<JsonNode> response, int expectedStatus) {
        assertEquals(expectedStatus, response.getStatusCode().value(),
                () -> "unexpected response: " + response.getBody());
        assertNotNull(response.getBody());
        return response.getBody();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void ensureDatabase() {
        try (Connection connection = DriverManager.getConnection(ADMIN_URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery(
                    "SELECT 1 FROM pg_database WHERE datname = '" + DATABASE + "'")) {
                if (!result.next()) {
                    statement.execute("CREATE DATABASE " + DATABASE);
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "The live PostgreSQL test requires a reachable server at " + ADMIN_URL
                            + " (set EIA_LIVE_POSTGRES=true and optional EIA_LIVE_* variables).",
                    exception);
        }
    }
}
