package com.eia.assumedclaims.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CommutationRepository {
    private static final RowMapper<Commutation> MAPPER = (rs, rowNum) -> new Commutation(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("assumed_claim_id", UUID.class),
            rs.getString("commutation_number"),
            CommutationType.valueOf(rs.getString("commutation_type")),
            rs.getBigDecimal("offer_amount"),
            rs.getBigDecimal("accepted_amount"),
            rs.getString("currency"),
            rs.getObject("effective_date", LocalDate.class),
            rs.getObject("valuation_date", LocalDate.class),
            CommutationBasis.valueOf(rs.getString("basis")),
            rs.getObject("release_document_id", UUID.class),
            CommutationStatus.valueOf(rs.getString("status")),
            rs.getObject("approved_by", UUID.class),
            ApproverRole.valueOf(rs.getString("approver_role")),
            instant(rs.getTimestamp("approved_at")),
            instant(rs.getTimestamp("executed_at")),
            rs.getBigDecimal("savings_amount"),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public CommutationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Commutation> findByClaim(UUID tenantId, UUID claimId) {
        return jdbc.query("""
                SELECT * FROM assumed_commutation
                 WHERE tenant_id = ? AND assumed_claim_id = ?
                """, MAPPER, tenantId, claimId).stream().findFirst();
    }

    public void insert(Commutation commutation) {
        jdbc.update("""
                INSERT INTO assumed_commutation (
                    id, tenant_id, assumed_claim_id, commutation_number, commutation_type,
                    offer_amount, accepted_amount, currency, effective_date, valuation_date, basis,
                    release_document_id, status, approved_by, approver_role, approved_at, executed_at,
                    savings_amount, version, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                commutation.id(),
                commutation.tenantId(),
                commutation.assumedClaimId(),
                commutation.commutationNumber(),
                commutation.commutationType().name(),
                commutation.offerAmount(),
                commutation.acceptedAmount(),
                commutation.currency(),
                Date.valueOf(commutation.effectiveDate()),
                commutation.valuationDate() == null ? null : Date.valueOf(commutation.valuationDate()),
                commutation.basis().name(),
                commutation.releaseDocumentId(),
                commutation.status().name(),
                commutation.approvedBy(),
                commutation.approverRole().name(),
                Timestamp.from(commutation.approvedAt()),
                commutation.executedAt() == null ? null : Timestamp.from(commutation.executedAt()),
                commutation.savingsAmount(),
                commutation.version(),
                Timestamp.from(commutation.createdAt()),
                Timestamp.from(commutation.updatedAt()));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
