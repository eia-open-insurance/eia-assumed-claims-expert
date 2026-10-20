package com.eia.assumedclaims.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@code claim_command_idempotency}, scoped by tenant, command
 * type, and key. The composite primary key is the concurrency arbiter: a
 * duplicate reservation is skipped with {@code ON CONFLICT DO NOTHING} and the
 * winner's committed row is replayed.
 */
@Repository
public class ClaimCommandIdempotencyRepository {
    private static final RowMapper<ClaimCommandIdempotencyRecord> MAPPER =
            (rs, rowNum) -> new ClaimCommandIdempotencyRecord(
                    rs.getString("command_type"),
                    rs.getString("request_hash"),
                    rs.getObject("claim_id", UUID.class),
                    rs.getString("response_body"));

    private final JdbcTemplate jdbc;
    private final String completeSql;

    public ClaimCommandIdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.completeSql = """
                UPDATE claim_command_idempotency
                   SET response_body = %s
                 WHERE tenant_id = ? AND command_type = ? AND idempotency_key = ?
                """.formatted(jsonParameter(jdbc));
    }

    public Optional<ClaimCommandIdempotencyRecord> find(UUID tenantId,
                                                       String commandType,
                                                       String key) {
        return jdbc.query("""
                        SELECT command_type, request_hash, claim_id, response_body
                          FROM claim_command_idempotency
                         WHERE tenant_id = ? AND command_type = ? AND idempotency_key = ?
                        """, MAPPER, tenantId, commandType, key).stream().findFirst();
    }

    /**
     * Reserves the key in the caller's transaction. A failed create rolls the
     * reservation back with the task/note and its event.
     *
     * @return true when this caller inserted the row; false when the key already exists
     */
    public boolean reserve(UUID tenantId,
                           String commandType,
                           String key,
                           String requestHash,
                           UUID claimId,
                           Instant now) {
        return jdbc.update("""
                        INSERT INTO claim_command_idempotency
                            (tenant_id, command_type, idempotency_key, request_hash, claim_id,
                             created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT DO NOTHING
                        """, tenantId, commandType, key, requestHash, claimId,
                Timestamp.from(now)) == 1;
    }

    /**
     * Stores the original 201 response body after the action succeeded.
     *
     * @return true when the reservation row was updated
     */
    public boolean complete(UUID tenantId, String commandType, String key, String responseBody) {
        return jdbc.update(completeSql, responseBody, tenantId, commandType, key) == 1;
    }

    /**
     * PostgreSQL needs an explicit JSONB cast for a bound varchar. H2 (used by
     * component tests) stores the body as text and accepts the plain parameter.
     */
    private static String jsonParameter(JdbcTemplate jdbc) {
        DataSource dataSource = jdbc.getDataSource();
        if (dataSource == null) {
            return "CAST(? AS JSONB)";
        }
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            return "H2".equalsIgnoreCase(product) ? "?" : "CAST(? AS JSONB)";
        } catch (SQLException ex) {
            return "CAST(? AS JSONB)";
        }
    }
}
