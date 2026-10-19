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
public class CashCallRepository {
    private static final RowMapper<CashCall> MAPPER = (rs, rowNum) -> new CashCall(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("assumed_claim_id", UUID.class),
            rs.getString("request_number"),
            rs.getBigDecimal("requested_amount"),
            rs.getString("currency"),
            rs.getObject("requested_at", LocalDate.class),
            rs.getObject("due_date", LocalDate.class),
            CashCallStatus.valueOf(rs.getString("status")),
            CashCallReason.valueOf(rs.getString("reason")),
            rs.getString("reason_text"),
            rs.getObject("approved_by", UUID.class),
            rs.getString("approver_role") == null ? null : ApproverRole.valueOf(rs.getString("approver_role")),
            instant(rs.getTimestamp("approved_at")),
            rs.getString("approval_status"),
            rs.getObject("payment_id", UUID.class),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public CashCallRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<CashCall> findByClaim(UUID tenantId, UUID claimId) {
        return jdbc.query("""
                SELECT * FROM assumed_cash_call
                 WHERE tenant_id = ? AND assumed_claim_id = ?
                 ORDER BY created_at DESC
                """, MAPPER, tenantId, claimId);
    }

    public Optional<CashCall> findById(UUID tenantId, UUID claimId, UUID id) {
        return jdbc.query("""
                SELECT * FROM assumed_cash_call
                 WHERE tenant_id = ? AND assumed_claim_id = ? AND id = ?
                """, MAPPER, tenantId, claimId, id).stream().findFirst();
    }

    public BigDecimal outstandingTotal(UUID tenantId, UUID claimId) {
        BigDecimal value = jdbc.queryForObject("""
                SELECT COALESCE(SUM(requested_amount), 0)
                  FROM assumed_cash_call
                 WHERE tenant_id = ? AND assumed_claim_id = ? AND status IN ('SUBMITTED', 'APPROVED')
                """, BigDecimal.class, tenantId, claimId);
        return value == null ? BigDecimal.ZERO : value;
    }

    public BigDecimal paidTotal(UUID tenantId, UUID claimId) {
        BigDecimal value = jdbc.queryForObject("""
                SELECT COALESCE(SUM(requested_amount), 0)
                  FROM assumed_cash_call
                 WHERE tenant_id = ? AND assumed_claim_id = ? AND status IN ('APPROVED', 'PAID')
                """, BigDecimal.class, tenantId, claimId);
        return value == null ? BigDecimal.ZERO : value;
    }

    public void insert(CashCall call) {
        jdbc.update("""
                INSERT INTO assumed_cash_call (
                    id, tenant_id, assumed_claim_id, request_number, requested_amount, currency,
                    requested_at, due_date, status, reason, reason_text, approved_by, approver_role,
                    approved_at, approval_status, payment_id, version, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                call.id(),
                call.tenantId(),
                call.assumedClaimId(),
                call.requestNumber(),
                call.requestedAmount(),
                call.currency(),
                Date.valueOf(call.requestedAt()),
                call.dueDate() == null ? null : Date.valueOf(call.dueDate()),
                call.status().name(),
                call.reason().name(),
                call.reasonText(),
                call.approvedBy(),
                call.approverRole() == null ? null : call.approverRole().name(),
                call.approvedAt() == null ? null : Timestamp.from(call.approvedAt()),
                call.approvalStatus(),
                call.paymentId(),
                call.version(),
                Timestamp.from(call.createdAt()),
                Timestamp.from(call.updatedAt()));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
