# Changelog

All notable changes to this repository are documented here.

The format is based on Keep a Changelog and the project uses Semantic Versioning.

## [Unreleased]

### Added

- Repository baseline.
- Spring Boot 3.4.0 expert slice: `assumed_claim` schema, internal list/get/create/status endpoints, ASSUMED book guard, structured errors, and JUnit controller and lifecycle tests.
- Second slice: loss advice and bordereau ingestion with duplicate detection, append-only reserve history with authority rules, cash calls, recoveries, commutation, disputes, financial projection, and append-only claim audit. Includes new service/controller tests and a live PostgreSQL smoke test (`liveTest`, `EIA_LIVE_POSTGRES=true`).
- Claim tasks and append-only notes:
  - Idempotent `claim_task` and `claim_note` DDL plus indexes and H2 test schema; task numbers
    and note numbers are unique per tenant, tasks carry a bigint optimistic version, and notes are
    append-only.
  - `GET|POST /api/v1/assumed-claims/claims/{claimId}/tasks` and `PATCH .../tasks/{taskId}`
    (also aliased under `/api/v1/assumed-claims/{claimId}` and `/internal/v1`), with
    title/priority/due-date validation, open-first due-date ordering, COMPLETE/CANCELLED
    transitions, and 409 on a stale task version.
  - `GET|POST /api/v1/assumed-claims/claims/{claimId}/notes`, newest first and append-only.
  - Tenant/ASSUMED-book scoping with 404 for a claim outside the tenant or book.
  - Outbox events written in the same transaction: `assumed.claim.task-created`,
    `assumed.claim.task-completed`, `assumed.claim.task-updated`, and
    `assumed.claim.note-added`; task/note writes bump the claim version so event aggregate
    versions stay monotonic, and rollback tests cover both resources.
- Idempotent task and note creation:
  - Optional `Idempotency-Key` (1-64 characters) on both POSTs, with a
    `claim_command_idempotency` table scoped by tenant, command type, and key and a stored
    `response_body` JSONB projection.
  - Same key and canonical body returns the original 201 projection with
    `Idempotent-Replay: true` and writes no duplicate row, version bump, or event; a different
    body returns 409 `EIA-RNS-4091` `IDEMPOTENCY_KEY_REUSED`; a missing key keeps the previous
    behavior.
  - The reservation shares the command transaction, so rollback leaves no key behind.
  - H2 integration tests cover task/note replay, conflict, missing key, tenant and command-type
    scoping, rollback, and event counts.
- Claim assignment:
  - `assigned_adjuster`, `assigned_team`, `assigned_at`, and `assigned_by` on `assumed_claim`,
    added with idempotent `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` statements in both the
    PostgreSQL and H2 schemas.
  - `PATCH /api/v1/assumed-claims/claims/{claimId}/assignment` with the shorter public and
    internal aliases, the ASSUMED book wall, an optimistic `expectedVersion` (409
    `EIA-RNS-7006` on a stale value), an optional team, and a same-assignment no-op.
  - `assumed.claim.assigned` written in the same transaction as the assignment, carrying the
    previous and new owner, `assignedAt`, and `assignedBy`; rollback tests prove the assignment,
    audit entry, and event commit or roll back together.
  - The claim projection and list/search expose `assignedAdjuster`, `assignedTeam`, and
    `assignedAt`, and list accepts `assignedAdjuster`/`assignedTeam` filters.
