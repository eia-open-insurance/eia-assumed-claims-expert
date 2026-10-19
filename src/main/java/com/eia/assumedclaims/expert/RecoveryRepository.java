package com.eia.assumedclaims.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public class RecoveryRepository {
    private static final RowMapper<Recovery> MAPPER = (rs, rowNum) -> new Recovery(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("assumed_claim_id", UUID.class),
            RecoveryType.valueOf(rs.getString("recovery_type")),
            rs.getBigDecimal("amount"),
            rs.getString("currency"),
            rs.getObject("received_date", LocalDate.class),
            RecoveryStatus.valueOf(rs.getString("status")),
            rs.getObject("source_party_id", UUID.class),
            rs.getObject("source_contract_id", UUID.class),
            rs.getBigDecimal("expected_amount"),
            rs.getObject("payment_id", UUID.class),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public RecoveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Recovery> findByClaim(UUID tenantId, UUID claimId) {
        return jdbc.query("""
                SELECT * FROM assumed_recovery
                 WHERE tenant_id = ? AND assumed_claim_id = ?
                 ORDER BY created_at DESC
                """, MAPPER, tenantId, claimId);
    }

    public BigDecimal consumedCapacity(UUID tenantId, UUID claimId) {
        BigDecimal value = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0)
                  FROM assumed_recovery
                 WHERE tenant_id = ? AND assumed_claim_id = ? AND status NOT IN ('DENIED', 'ABANDONED')
                """, BigDecimal.class, tenantId, claimId);
        return value == null ? BigDecimal.ZERO : value;
    }

    public BigDecimal recognizedTotal(UUID tenantId, UUID claimId) {
        BigDecimal value = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0)
                  FROM assumed_recovery
                 WHERE tenant_id = ? AND assumed_claim_id = ? AND status IN ('AGREED', 'RECEIVED')
                """, BigDecimal.class, tenantId, claimId);
        return value == null ? BigDecimal.ZERO : value;
    }

    public void insert(Recovery recovery) {
        jdbc.update("""
                INSERT INTO assumed_recovery (
                    id, tenant_id, assumed_claim_id, recovery_type, amount, currency, received_date,
                    status, source_party_id, source_contract_id, expected_amount, payment_id,
                    version, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                recovery.id(),
                recovery.tenantId(),
                recovery.assumedClaimId(),
                recovery.recoveryType().name(),
                recovery.amount(),
                recovery.currency(),
                Date.valueOf(recovery.receivedDate()),
                recovery.status().name(),
                recovery.sourcePartyId(),
                recovery.sourceContractId(),
                recovery.expectedAmount(),
                recovery.paymentId(),
                recovery.version(),
                Timestamp.from(recovery.createdAt()),
                Timestamp.from(recovery.updatedAt()));
    }
}
