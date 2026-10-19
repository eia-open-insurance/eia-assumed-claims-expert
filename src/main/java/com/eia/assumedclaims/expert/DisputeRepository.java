package com.eia.assumedclaims.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DisputeRepository {
    private static final RowMapper<Dispute> MAPPER = (rs, rowNum) -> new Dispute(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("assumed_claim_id", UUID.class),
            rs.getString("dispute_number"),
            DisputeType.valueOf(rs.getString("dispute_type")),
            rs.getString("reason"),
            rs.getBigDecimal("contested_amount"),
            rs.getString("currency"),
            DisputeStatus.valueOf(rs.getString("status")),
            rs.getString("resolution"),
            rs.getObject("raised_at", LocalDate.class),
            rs.getObject("resolved_at", LocalDate.class),
            rs.getBoolean("arbitration_flag"),
            rs.getObject("raised_by_party_id", UUID.class),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public DisputeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Dispute> findByClaim(UUID tenantId, UUID claimId) {
        return jdbc.query("""
                SELECT * FROM assumed_dispute
                 WHERE tenant_id = ? AND assumed_claim_id = ?
                 ORDER BY created_at DESC
                """, MAPPER, tenantId, claimId);
    }

    public Optional<Dispute> findById(UUID tenantId, UUID claimId, UUID id) {
        return jdbc.query("""
                SELECT * FROM assumed_dispute
                 WHERE tenant_id = ? AND assumed_claim_id = ? AND id = ?
                """, MAPPER, tenantId, claimId, id).stream().findFirst();
    }

    public boolean existsOpen(UUID tenantId, UUID claimId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM assumed_dispute
                     WHERE tenant_id = ? AND assumed_claim_id = ?
                       AND status IN ('OPEN', 'UNDER_REVIEW', 'NEGOTIATING', 'ESCALATED', 'ARBITRATION')
                )
                """, Boolean.class, tenantId, claimId);
        return Boolean.TRUE.equals(exists);
    }

    public void insert(Dispute dispute) {
        jdbc.update("""
                INSERT INTO assumed_dispute (
                    id, tenant_id, assumed_claim_id, dispute_number, dispute_type, reason,
                    contested_amount, currency, status, resolution, raised_at, resolved_at,
                    arbitration_flag, raised_by_party_id, version, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                dispute.id(),
                dispute.tenantId(),
                dispute.assumedClaimId(),
                dispute.disputeNumber(),
                dispute.disputeType().name(),
                dispute.reason(),
                dispute.contestedAmount(),
                dispute.currency(),
                dispute.status().name(),
                dispute.resolution(),
                Date.valueOf(dispute.raisedAt()),
                dispute.resolvedAt() == null ? null : Date.valueOf(dispute.resolvedAt()),
                dispute.arbitrationFlag(),
                dispute.raisedByPartyId(),
                dispute.version(),
                Timestamp.from(dispute.createdAt()),
                Timestamp.from(dispute.updatedAt()));
    }

    public Dispute resolve(
            UUID tenantId,
            UUID claimId,
            UUID disputeId,
            DisputeStatus status,
            String resolution,
            LocalDate resolvedAt
    ) {
        int updated = jdbc.update("""
                UPDATE assumed_dispute
                   SET status = ?, resolution = ?, resolved_at = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND assumed_claim_id = ? AND id = ?
                   AND status IN ('OPEN', 'UNDER_REVIEW', 'NEGOTIATING', 'ESCALATED', 'ARBITRATION')
                """,
                status.name(),
                resolution,
                Date.valueOf(resolvedAt),
                Timestamp.from(java.time.Instant.now()),
                tenantId,
                claimId,
                disputeId);
        if (updated == 0) {
            throw ApiException.disputeNotFound(disputeId);
        }
        return findById(tenantId, claimId, disputeId)
                .orElseThrow(() -> ApiException.disputeNotFound(disputeId));
    }
}
