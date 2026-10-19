package com.eia.assumedclaims.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ReserveRepository {
    private static final RowMapper<ReserveHistoryEntry> MAPPER = (rs, rowNum) -> new ReserveHistoryEntry(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("assumed_claim_id", UUID.class),
            ReserveComponent.valueOf(rs.getString("component")),
            rs.getInt("sequence"),
            rs.getBigDecimal("amount"),
            rs.getString("currency"),
            rs.getObject("effective_date", LocalDate.class),
            ReserveReason.valueOf(rs.getString("reason_code")),
            rs.getString("reason_text"),
            ReserveAuthority.valueOf(rs.getString("authority")),
            rs.getBoolean("authority_required"),
            rs.getObject("approved_by", UUID.class),
            instant(rs.getTimestamp("approved_at")),
            rs.getBoolean("is_current"),
            rs.getInt("version"),
            rs.getObject("created_by", UUID.class),
            rs.getTimestamp("created_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public ReserveRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ReserveHistoryEntry> findByClaim(UUID tenantId, UUID claimId) {
        return jdbc.query("""
                SELECT * FROM assumed_reserve_history
                 WHERE tenant_id = ? AND assumed_claim_id = ?
                 ORDER BY effective_date DESC, sequence DESC, created_at DESC
                """, MAPPER, tenantId, claimId);
    }

    public List<ReserveHistoryEntry> findCurrent(UUID tenantId, UUID claimId) {
        return jdbc.query("""
                SELECT * FROM assumed_reserve_history
                 WHERE tenant_id = ? AND assumed_claim_id = ? AND is_current = TRUE
                 ORDER BY component ASC
                """, MAPPER, tenantId, claimId);
    }

    public Optional<ReserveHistoryEntry> findLatest(UUID tenantId, UUID claimId, ReserveComponent component) {
        return jdbc.query("""
                SELECT * FROM assumed_reserve_history
                 WHERE tenant_id = ? AND assumed_claim_id = ? AND component = ?
                 ORDER BY effective_date DESC, sequence DESC, created_at DESC
                 LIMIT 1
                """, MAPPER, tenantId, claimId, component.name()).stream().findFirst();
    }

    public BigDecimal currentTotal(UUID tenantId, UUID claimId) {
        BigDecimal value = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0)
                  FROM assumed_reserve_history
                 WHERE tenant_id = ? AND assumed_claim_id = ? AND is_current = TRUE
                """, BigDecimal.class, tenantId, claimId);
        return value == null ? BigDecimal.ZERO : value;
    }

    public BigDecimal currentComponentTotal(UUID tenantId, UUID claimId, ReserveComponent component) {
        BigDecimal value = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0)
                  FROM assumed_reserve_history
                 WHERE tenant_id = ? AND assumed_claim_id = ? AND is_current = TRUE AND component = ?
                """, BigDecimal.class, tenantId, claimId, component.name());
        return value == null ? BigDecimal.ZERO : value;
    }

    public int nextSequence(UUID tenantId, UUID claimId, ReserveComponent component) {
        Integer value = jdbc.queryForObject("""
                SELECT COALESCE(MAX(sequence), 0) + 1
                  FROM assumed_reserve_history
                 WHERE tenant_id = ? AND assumed_claim_id = ? AND component = ?
                """, Integer.class, tenantId, claimId, component.name());
        return value == null ? 1 : value;
    }

    public void supersedeCurrent(UUID tenantId, UUID claimId, ReserveComponent component) {
        jdbc.update("""
                UPDATE assumed_reserve_history
                   SET is_current = FALSE
                 WHERE tenant_id = ? AND assumed_claim_id = ? AND component = ? AND is_current = TRUE
                """, tenantId, claimId, component.name());
    }

    public void insert(ReserveHistoryEntry entry) {
        jdbc.update("""
                INSERT INTO assumed_reserve_history (
                    id, tenant_id, assumed_claim_id, component, sequence, amount, currency,
                    effective_date, reason_code, reason_text, authority, authority_required,
                    approved_by, approved_at, is_current, version, created_by, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                entry.id(),
                entry.tenantId(),
                entry.assumedClaimId(),
                entry.component().name(),
                entry.sequence(),
                entry.amount(),
                entry.currency(),
                Date.valueOf(entry.effectiveDate()),
                entry.reasonCode().name(),
                entry.reasonText(),
                entry.authority().name(),
                entry.authorityRequired(),
                entry.approvedBy(),
                entry.approvedAt() == null ? null : Timestamp.from(entry.approvedAt()),
                entry.isCurrent(),
                entry.version(),
                entry.createdBy(),
                Timestamp.from(entry.createdAt()));
    }

    public void deactivateAll(UUID tenantId, UUID claimId) {
        jdbc.update("""
                UPDATE assumed_reserve_history
                   SET is_current = FALSE
                 WHERE tenant_id = ? AND assumed_claim_id = ? AND is_current = TRUE
                """, tenantId, claimId);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
