package com.eia.assumedclaims.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class ClaimAuditRepository {
    private static final RowMapper<ClaimAuditEntry> MAPPER = (rs, rowNum) -> new ClaimAuditEntry(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("assumed_claim_id", UUID.class),
            AuditEventType.valueOf(rs.getString("event_type")),
            rs.getString("actor"),
            rs.getString("detail"),
            rs.getTimestamp("occurred_at").toInstant(),
            rs.getLong("sequence")
    );

    private final JdbcTemplate jdbc;

    public ClaimAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void append(UUID tenantId, UUID claimId, AuditEventType eventType, String actor, String detail) {
        jdbc.update("""
                INSERT INTO assumed_claim_audit (
                    id, tenant_id, assumed_claim_id, event_type, actor, detail, occurred_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                UuidV7.generate(),
                tenantId,
                claimId,
                eventType.name(),
                actor == null || actor.isBlank() ? "system" : actor.trim(),
                detail,
                Timestamp.from(Instant.now()));
    }

    public List<ClaimAuditEntry> findByClaim(UUID tenantId, UUID claimId) {
        return jdbc.query("""
                SELECT id, tenant_id, assumed_claim_id, event_type, actor, detail, occurred_at, sequence
                  FROM assumed_claim_audit
                 WHERE tenant_id = ? AND assumed_claim_id = ?
                 ORDER BY sequence ASC
                """, MAPPER, tenantId, claimId);
    }
}
