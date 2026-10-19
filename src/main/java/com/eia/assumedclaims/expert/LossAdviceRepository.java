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
public class LossAdviceRepository {
    private static final RowMapper<LossAdvice> MAPPER = (rs, rowNum) -> new LossAdvice(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("assumed_claim_id", UUID.class),
            rs.getString("advice_number"),
            rs.getString("cedent_claim_number"),
            AdviceType.valueOf(rs.getString("advice_type")),
            rs.getObject("advice_date", LocalDate.class),
            rs.getObject("received_date", LocalDate.class),
            rs.getObject("period_from", LocalDate.class),
            rs.getObject("period_to", LocalDate.class),
            rs.getBigDecimal("reported_amount"),
            rs.getBigDecimal("paid_amount"),
            rs.getBigDecimal("outstanding_amount"),
            rs.getBigDecimal("expense_amount"),
            rs.getBigDecimal("reserve_amount"),
            rs.getBigDecimal("recovery_amount"),
            rs.getString("currency"),
            rs.getBigDecimal("cession_pct"),
            rs.getBigDecimal("ceded_amount"),
            Boolean.TRUE.equals(rs.getObject("layer_attachment_met", Boolean.class)),
            AdviceStatus.valueOf(rs.getString("status")),
            rs.getString("rejection_reason"),
            rs.getObject("source_document_id", UUID.class),
            rs.getObject("bordereau_id", UUID.class),
            rs.getObject("bordereau_line_number", Integer.class),
            AdviceSource.valueOf(rs.getString("source")),
            rs.getInt("version"),
            instant(rs.getTimestamp("posted_at")),
            rs.getString("notes"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public LossAdviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<LossAdvice> findByClaim(UUID tenantId, UUID claimId) {
        return jdbc.query("""
                SELECT *
                  FROM assumed_loss_advice
                 WHERE tenant_id = ? AND assumed_claim_id = ?
                 ORDER BY advice_date DESC, created_at DESC
                """, MAPPER, tenantId, claimId);
    }

    public List<LossAdvice> findByBordereau(UUID tenantId, UUID bordereauId) {
        return jdbc.query("""
                SELECT *
                  FROM assumed_loss_advice
                 WHERE tenant_id = ? AND bordereau_id = ?
                 ORDER BY bordereau_line_number ASC
                """, MAPPER, tenantId, bordereauId);
    }

    public Optional<LossAdvice> findById(UUID tenantId, UUID claimId, UUID adviceId) {
        return jdbc.query("""
                SELECT *
                  FROM assumed_loss_advice
                 WHERE tenant_id = ? AND assumed_claim_id = ? AND id = ?
                """, MAPPER, tenantId, claimId, adviceId).stream().findFirst();
    }

    public boolean existsByAdviceNumber(UUID tenantId, UUID claimId, String adviceNumber) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM assumed_loss_advice
                     WHERE tenant_id = ? AND assumed_claim_id = ? AND advice_number = ?
                )
                """, Boolean.class, tenantId, claimId, adviceNumber);
        return Boolean.TRUE.equals(exists);
    }

    public boolean existsAccepted(UUID tenantId, UUID claimId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM assumed_loss_advice
                     WHERE tenant_id = ? AND assumed_claim_id = ? AND status = 'POSTED'
                )
                """, Boolean.class, tenantId, claimId);
        return Boolean.TRUE.equals(exists);
    }

    public long countByStatus(UUID tenantId, UUID claimId, AdviceStatus status) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM assumed_loss_advice
                 WHERE tenant_id = ? AND assumed_claim_id = ? AND status = ?
                """, Long.class, tenantId, claimId, status.name());
        return count == null ? 0 : count;
    }

    public void insert(LossAdvice advice) {
        jdbc.update("""
                INSERT INTO assumed_loss_advice (
                    id, tenant_id, assumed_claim_id, bordereau_id, bordereau_line_number, advice_number,
                    cedent_claim_number, advice_type, advice_date, received_date, period_from, period_to,
                    reported_amount, paid_amount, outstanding_amount, expense_amount, reserve_amount,
                    recovery_amount, currency, cession_pct, ceded_amount, layer_attachment_met, status,
                    rejection_reason, source_document_id, source, version, posted_at, notes, created_at,
                    updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                advice.id(),
                advice.tenantId(),
                advice.assumedClaimId(),
                advice.bordereauId(),
                advice.bordereauLineNumber(),
                advice.adviceNumber(),
                advice.cedentClaimNumber(),
                advice.adviceType().name(),
                Date.valueOf(advice.adviceDate()),
                Date.valueOf(advice.receivedDate()),
                advice.periodFrom() == null ? null : Date.valueOf(advice.periodFrom()),
                advice.periodTo() == null ? null : Date.valueOf(advice.periodTo()),
                advice.reportedAmount(),
                advice.paidAmount(),
                advice.outstandingAmount(),
                advice.expenseAmount(),
                advice.reserveAmount(),
                advice.recoveryAmount(),
                advice.currency(),
                advice.cessionPct(),
                advice.cededAmount(),
                advice.layerAttachmentMet(),
                advice.status().name(),
                advice.rejectionReason(),
                advice.sourceDocumentId(),
                advice.source().name(),
                advice.version(),
                advice.postedAt() == null ? null : Timestamp.from(advice.postedAt()),
                advice.notes(),
                Timestamp.from(advice.createdAt()),
                Timestamp.from(advice.updatedAt()));
    }

    public LossAdvice accept(UUID tenantId, UUID claimId, UUID adviceId, Instant postedAt) {
        int updated = jdbc.update("""
                UPDATE assumed_loss_advice
                   SET status = 'POSTED', posted_at = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND assumed_claim_id = ? AND id = ? AND status = 'RECEIVED'
                """,
                Timestamp.from(postedAt),
                Timestamp.from(postedAt),
                tenantId,
                claimId,
                adviceId);
        if (updated == 0) {
            throw ApiException.adviceNotFound(adviceId);
        }
        return findById(tenantId, claimId, adviceId)
                .orElseThrow(() -> ApiException.adviceNotFound(adviceId));
    }

    /** Ceded paid amount from accepted advices; a supplied ceded amount wins over the percentage. */
    public BigDecimal cededPaid(UUID tenantId, UUID claimId) {
        BigDecimal value = jdbc.queryForObject("""
                SELECT COALESCE(SUM(
                    CASE WHEN ceded_amount IS NOT NULL THEN ceded_amount
                         ELSE COALESCE(paid_amount, 0) * COALESCE(cession_pct, 1)
                    END), 0)
                  FROM assumed_loss_advice
                 WHERE tenant_id = ? AND assumed_claim_id = ? AND status = 'POSTED'
                """, BigDecimal.class, tenantId, claimId);
        return value == null ? BigDecimal.ZERO : value;
    }

    /** Ceded outstanding amount from accepted advices. */
    public BigDecimal cededOutstanding(UUID tenantId, UUID claimId) {
        BigDecimal value = jdbc.queryForObject("""
                SELECT COALESCE(SUM(
                    CASE WHEN ceded_amount IS NOT NULL THEN 0
                         ELSE COALESCE(outstanding_amount, 0) * COALESCE(cession_pct, 1)
                    END), 0)
                  FROM assumed_loss_advice
                 WHERE tenant_id = ? AND assumed_claim_id = ? AND status = 'POSTED'
                """, BigDecimal.class, tenantId, claimId);
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * Advised loss capacity for recoveries: the ceded reported amount of accepted advices, with
     * ceded paid as a floor. A recovery may not exceed this cap (RNS-10).
     */
    public BigDecimal advisedLoss(UUID tenantId, UUID claimId) {
        BigDecimal advised = jdbc.queryForObject("""
                SELECT COALESCE(SUM(
                    CASE WHEN ceded_amount IS NOT NULL THEN ceded_amount
                         ELSE COALESCE(reported_amount, 0) * COALESCE(cession_pct, 1)
                    END), 0)
                  FROM assumed_loss_advice
                 WHERE tenant_id = ? AND assumed_claim_id = ? AND status = 'POSTED'
                """, BigDecimal.class, tenantId, claimId);
        BigDecimal value = advised == null ? BigDecimal.ZERO : advised;
        BigDecimal paid = cededPaid(tenantId, claimId);
        return paid.compareTo(value) > 0 ? paid : value;
    }

    public Instant lastAdviceAt(UUID tenantId, UUID claimId) {
        Timestamp value = jdbc.queryForObject("""
                SELECT MAX(created_at)
                  FROM assumed_loss_advice
                 WHERE tenant_id = ? AND assumed_claim_id = ?
                """, Timestamp.class, tenantId, claimId);
        return value == null ? null : value.toInstant();
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
