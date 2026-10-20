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
