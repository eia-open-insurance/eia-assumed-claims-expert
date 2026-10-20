package com.eia.assumedclaims.expert;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Shared values for the claim task and note tests. */
final class TaskNoteFixtures {
    static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    static final UUID CLAIM_ID = UUID.fromString("0192f3a4-5b1c-7e2d-9a3f-6c8d1e0b4a72");
    static final UUID CONTRACT_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    static final UUID TASK_ID = UUID.fromString("0192f3a4-aaaa-7aaa-8aaa-000000000001");
    static final UUID NOTE_ID = UUID.fromString("0192f3a4-bbbb-7bbb-8bbb-000000000002");
    static final LocalDate LOSS_DATE = LocalDate.of(2026, 9, 1);
    static final LocalDate REPORT_DATE = LocalDate.of(2026, 9, 2);
    static final LocalDate TASK_DUE_DATE = LocalDate.of(2026, 4, 15);
    static final Instant NOW = Instant.parse("2026-03-18T09:00:00Z");

    private TaskNoteFixtures() {
    }

    static AssumedClaim claim(AssumedClaimStatus status, int version) {
        return new AssumedClaim(CLAIM_ID, TENANT, "ASSUMED", "RNS-2026-0000001", CONTRACT_ID,
                "CED-778", status, LOSS_DATE, REPORT_DATE, new BigDecimal("100000.0000"), "USD",
                version, NOW, NOW);
    }

    static ClaimTask task(String status, long version) {
        return task(status, version, TASK_DUE_DATE);
    }

    static ClaimTask task(String status, long version, LocalDate dueDate) {
        return new ClaimTask(TASK_ID, TENANT, CLAIM_ID, "TSK-2026-0000001",
                "Review medical records", "Order records and index the file", dueDate, "HIGH",
                status, "adjuster@example.com", version, NOW, NOW,
                "COMPLETE".equals(status) ? NOW : null);
    }

    static ClaimNote note(String noteType, String body) {
        return new ClaimNote(NOTE_ID, TENANT, CLAIM_ID, "NTE-2026-0000001", noteType, body,
                "adjuster@example.com", NOW);
    }
}
