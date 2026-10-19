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
public class BordereauRepository {
    private static final RowMapper<BordereauBatch> MAPPER = (rs, rowNum) -> new BordereauBatch(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("assumed_claim_id", UUID.class),
            rs.getString("bordereau_reference"),
            rs.getString("cedent_claim_number"),
            rs.getObject("advice_date", LocalDate.class),
            rs.getBigDecimal("reported_amount_total"),
            rs.getString("currency"),
            BordereauStatus.valueOf(rs.getString("status")),
            AdviceSource.valueOf(rs.getString("source")),
            rs.getInt("version"),
            rs.getInt("line_count"),
            rs.getInt("accepted_count"),
            rs.getInt("duplicate_count"),
            rs.getInt("rejected_count"),
            rs.getString("payload_hash"),
            rs.getTimestamp("received_at").toInstant(),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public BordereauRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<BordereauBatch> findByReference(UUID tenantId, String reference) {
        return jdbc.query("""
                SELECT * FROM assumed_bordereau
                 WHERE tenant_id = ? AND bordereau_reference = ?
                """, MAPPER, tenantId, reference).stream().findFirst();
    }

    public Optional<BordereauBatch> findById(UUID tenantId, UUID id) {
        return jdbc.query("""
                SELECT * FROM assumed_bordereau
                 WHERE tenant_id = ? AND id = ?
                """, MAPPER, tenantId, id).stream().findFirst();
    }

    public List<BordereauBatch> findByClaim(UUID tenantId, UUID claimId) {
        return jdbc.query("""
                SELECT * FROM assumed_bordereau
                 WHERE tenant_id = ? AND assumed_claim_id = ?
                 ORDER BY received_at DESC
                """, MAPPER, tenantId, claimId);
    }

    public void insertPending(BordereauBatch batch) {
        jdbc.update("""
                INSERT INTO assumed_bordereau (
                    id, tenant_id, assumed_claim_id, bordereau_reference, cedent_claim_number, advice_date,
                    reported_amount_total, currency, status, source, version, line_count, accepted_count,
                    duplicate_count, rejected_count, payload_hash, received_at, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, 1, 0, 0, 0, 0, ?, ?, ?, ?)
                """,
                batch.id(),
                batch.tenantId(),
                batch.assumedClaimId(),
                batch.bordereauReference(),
                batch.cedentClaimNumber(),
                Date.valueOf(batch.adviceDate()),
                batch.currency(),
                BordereauStatus.PROCESSED.name(),
                AdviceSource.BORDEREAU.name(),
                batch.payloadHash(),
                Timestamp.from(batch.receivedAt()),
                Timestamp.from(batch.createdAt()),
                Timestamp.from(batch.updatedAt()));
    }

    public BordereauBatch complete(
            UUID tenantId,
            UUID id,
            BigDecimal reportedTotal,
            BordereauStatus status,
            int lineCount,
            int acceptedCount,
            int duplicateCount,
            int rejectedCount,
            Instant updatedAt
    ) {
        int updated = jdbc.update("""
                UPDATE assumed_bordereau
                   SET reported_amount_total = ?, status = ?, version = version + 1,
                       line_count = ?, accepted_count = ?, duplicate_count = ?, rejected_count = ?,
                       updated_at = ?
                 WHERE tenant_id = ? AND id = ?
                """,
                reportedTotal,
                status.name(),
                lineCount,
                acceptedCount,
                duplicateCount,
                rejectedCount,
                Timestamp.from(updatedAt),
                tenantId,
                id);
        if (updated == 0) {
            throw new IllegalStateException("Bordereau batch disappeared during ingest: " + id);
        }
        return findById(tenantId, id).orElseThrow(() -> new IllegalStateException("Bordereau batch missing: " + id));
    }
}
