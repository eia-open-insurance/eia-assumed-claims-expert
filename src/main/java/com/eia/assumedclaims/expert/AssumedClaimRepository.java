package com.eia.assumedclaims.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AssumedClaimRepository {
    private static final String SELECT_COLUMNS = """
            SELECT id, tenant_id, business_book, assumed_claim_number, contract_id, cedent_claim_number,
                   status, loss_date, report_date, reserve_amount, reserve_currency,
                   assigned_adjuster, assigned_team, assigned_at, version,
                   created_at, updated_at
              FROM assumed_claim
            """;

    private static final RowMapper<AssumedClaim> MAPPER = (rs, rowNum) -> {
        Timestamp assignedAt = rs.getTimestamp("assigned_at");
        return new AssumedClaim(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("business_book"),
                rs.getString("assumed_claim_number"),
                rs.getObject("contract_id", UUID.class),
                rs.getString("cedent_claim_number"),
                AssumedClaimStatus.valueOf(rs.getString("status")),
                rs.getObject("loss_date", LocalDate.class),
                rs.getObject("report_date", LocalDate.class),
                rs.getBigDecimal("reserve_amount"),
                rs.getString("reserve_currency"),
                rs.getString("assigned_adjuster"),
                rs.getString("assigned_team"),
                assignedAt == null ? null : assignedAt.toInstant(),
                rs.getInt("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    };

    private final JdbcTemplate jdbc;
    private final NumberSequences numbers;

    public AssumedClaimRepository(JdbcTemplate jdbc, NumberSequences numbers) {
        this.jdbc = jdbc;
        this.numbers = numbers;
    }

    public List<AssumedClaim> findAll(UUID tenantId) {
        return findAll(tenantId, null, null);
    }

    /**
     * Lists ASSUMED-book claims, optionally narrowing to the current assignee.
     * A blank filter is ignored; the assignment filters are exact matches so a
     * list/search screen can page by owner or team.
     */
    public List<AssumedClaim> findAll(UUID tenantId, String assignedAdjuster, String assignedTeam) {
        StringBuilder sql = new StringBuilder(SELECT_COLUMNS)
                .append(" WHERE tenant_id = ? AND business_book = 'ASSUMED'");
        List<Object> arguments = new java.util.ArrayList<>();
        arguments.add(tenantId);
        if (StringUtils.hasText(assignedAdjuster)) {
            sql.append(" AND assigned_adjuster = ?");
            arguments.add(assignedAdjuster.trim());
        }
        if (StringUtils.hasText(assignedTeam)) {
            sql.append(" AND assigned_team = ?");
            arguments.add(assignedTeam.trim());
        }
        sql.append(" ORDER BY created_at DESC, assumed_claim_number DESC");
        return jdbc.query(sql.toString(), MAPPER, arguments.toArray());
    }

    public Optional<AssumedClaim> findById(UUID tenantId, UUID id) {
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE tenant_id = ? AND id = ? AND business_book = 'ASSUMED'
                """, MAPPER, tenantId, id).stream().findFirst();
    }

    public AssumedClaim create(CreateAssumedClaimRequest request) {
        UUID id = UuidV7.generate();
        String assumedClaimNumber = numbers.nextClaimNumber();
        Instant now = Instant.now();

        jdbc.update("""
                INSERT INTO assumed_claim (
                    id, tenant_id, business_book, assumed_claim_number, contract_id, cedent_claim_number,
                    status, loss_date, report_date, reserve_amount, reserve_currency, version,
                    created_at, updated_at
                )
                VALUES (?, ?, 'ASSUMED', ?, ?, ?, 'NOTIFIED', ?, ?, ?, ?, 0, ?, ?)
                """,
                id,
                request.tenantId(),
                assumedClaimNumber,
                request.contractId(),
                normalize(request.cedentClaimNumber()),
                request.lossDate() == null ? null : java.sql.Date.valueOf(request.lossDate()),
                request.reportDate() == null ? null : java.sql.Date.valueOf(request.reportDate()),
                request.reserveAmount(),
                request.reserveCurrency(),
                Timestamp.from(now),
                Timestamp.from(now));

        return findById(request.tenantId(), id)
                .orElseThrow(() -> new IllegalStateException("Assumed claim was not persisted: " + id));
    }

    public AssumedClaim changeStatus(UUID tenantId, UUID id, AssumedClaimStatus targetStatus, int expectedVersion) {
        AssumedClaim current = findById(tenantId, id)
                .orElseThrow(() -> ApiException.assumedClaimNotFound(id));

        if (current.version() != expectedVersion) {
            throw ApiException.versionConflict(expectedVersion);
        }
        if (!current.status().canTransitionTo(targetStatus)) {
            throw ApiException.invalidTransition(current.status(), targetStatus);
        }

        int updated = jdbc.update("""
                UPDATE assumed_claim
                   SET status = ?, version = version + 1, updated_at = ?
                 WHERE id = ? AND tenant_id = ? AND business_book = 'ASSUMED' AND version = ?
                """,
                targetStatus.name(),
                Timestamp.from(Instant.now()),
                id,
                tenantId,
                expectedVersion);

        if (updated == 0) {
            throw ApiException.versionConflict(expectedVersion);
        }
        return findById(tenantId, id)
                .orElseThrow(() -> ApiException.assumedClaimNotFound(id));
    }

    /**
     * Applies an assignment change using the claim row version as the
     * optimistic-concurrency token. Returns empty when the expected version no
     * longer matches, which the service maps to the standard 409 conflict.
     */
    public Optional<AssumedClaim> assign(UUID tenantId,
                                         UUID id,
                                         String assignedAdjuster,
                                         String assignedTeam,
                                         String assignedBy,
                                         Instant assignedAt,
                                         int expectedVersion) {
        int updated = jdbc.update("""
                UPDATE assumed_claim
                   SET assigned_adjuster = ?, assigned_team = ?, assigned_at = ?, assigned_by = ?,
                       version = version + 1, updated_at = ?
                 WHERE id = ? AND tenant_id = ? AND business_book = 'ASSUMED' AND version = ?
                """,
                assignedAdjuster,
                assignedTeam,
                Timestamp.from(assignedAt),
                assignedBy,
                Timestamp.from(assignedAt),
                id,
                tenantId,
                expectedVersion);

        if (updated == 0) {
            return Optional.empty();
        }
        return findById(tenantId, id);
    }

    /**
     * Applies the terminal state for an executed commutation. Reserve history has already been
     * deactivated by {@link ReserveRepository}; the claim is closed directly because the commutation
     * operation owns the COMMUTED-to-CLOSED step.
     */
    public AssumedClaim closeAfterCommutation(UUID tenantId, UUID id) {
        int updated = jdbc.update("""
                UPDATE assumed_claim
                   SET status = 'CLOSED', reserve_amount = 0, version = version + 1, updated_at = ?
                 WHERE id = ? AND tenant_id = ? AND business_book = 'ASSUMED'
                """,
                Timestamp.from(Instant.now()),
                id,
                tenantId);
        if (updated == 0) {
            throw ApiException.assumedClaimNotFound(id);
        }
        return findById(tenantId, id)
                .orElseThrow(() -> ApiException.assumedClaimNotFound(id));
    }

    /** Bumps the aggregate version after a material child-record write. */
    public void touch(UUID tenantId, UUID id) {
        jdbc.update("""
                UPDATE assumed_claim
                   SET version = version + 1, updated_at = ?
                 WHERE id = ? AND tenant_id = ? AND business_book = 'ASSUMED'
                """,
                Timestamp.from(Instant.now()),
                id,
                tenantId);
    }

    public void updateReserveAmount(UUID tenantId, UUID id, BigDecimal reserveAmount) {
        jdbc.update("""
                UPDATE assumed_claim
                   SET reserve_amount = ?, version = version + 1, updated_at = ?
                 WHERE id = ? AND tenant_id = ? AND business_book = 'ASSUMED'
                """,
                reserveAmount,
                Timestamp.from(Instant.now()),
                id,
                tenantId);
    }

    private static String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
