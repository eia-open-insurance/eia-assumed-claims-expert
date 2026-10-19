package com.eia.assumedclaims.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AssumedClaimRepository {
    private static final String SELECT_COLUMNS = """
            SELECT id, tenant_id, business_book, assumed_claim_number, contract_id, cedent_claim_number,
                   status, loss_date, report_date, reserve_amount, reserve_currency, version,
                   created_at, updated_at
              FROM assumed_claim
            """;

    private static final RowMapper<AssumedClaim> MAPPER = (rs, rowNum) -> new AssumedClaim(
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
            rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public AssumedClaimRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AssumedClaim> findAll(UUID tenantId) {
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE tenant_id = ? AND business_book = 'ASSUMED'
                 ORDER BY created_at DESC, assumed_claim_number DESC
                """, MAPPER, tenantId);
    }

    public Optional<AssumedClaim> findById(UUID tenantId, UUID id) {
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE tenant_id = ? AND id = ? AND business_book = 'ASSUMED'
                """, MAPPER, tenantId, id).stream().findFirst();
    }

    public AssumedClaim create(CreateAssumedClaimRequest request) {
        UUID id = UuidV7.generate();
        String assumedClaimNumber = "RNS-" + Year.now(ZoneOffset.UTC).getValue() + "-"
                + String.format("%07d", nextNumber());
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

    private long nextNumber() {
        Long value = jdbc.queryForObject("SELECT nextval('assumed_claim_number_seq')", Long.class);
        if (value == null) {
            throw new IllegalStateException("assumed_claim_number_seq returned no value");
        }
        return value;
    }

    private static String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
