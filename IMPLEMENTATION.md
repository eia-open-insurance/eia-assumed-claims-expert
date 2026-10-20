# Implementation plan

1. Add the Gradle build, Spring Boot application class, and runtime configuration.
2. Add domain or expert dependencies according to the specification.
3. Add unit, component, contract, and integration tests.
4. Add database migrations in the expert repository only.
5. Document the local Gradle verification step.
6. Add Docker image build and Helm values for the target environment.
7. Publish OpenAPI/AsyncAPI contracts to `eia-contracts`.
8. Add the service to the release manifest.

## Implemented

- Claim aggregate, lifecycle, ASSUMED wall, structured errors.
- Loss advice and bordereau ingestion, append-only reserve history, cash calls, recoveries,
  commutation, disputes, financial projection, and append-only audit.
- Claim tasks (optimistic version, open-first due-date ordering, COMPLETE/CANCELLED transitions)
  and append-only diary notes (newest first) under both the public `/api/v1` prefix and the
  internal expert alias, with 404 outside the tenant or ASSUMED book.
- Idempotent task and note creation via an optional `Idempotency-Key` (1-64 characters):
  `claim_command_idempotency` is scoped by tenant, command type, and key, stores the original
  response body as JSONB, and returns it with `Idempotent-Replay: true`; a different body is
  409 `EIA-RNS-4091`. The reservation shares the command transaction, so rollback and replay
  leave no stray row or event.
- Transactional outbox production (`com.eia:libs`): claim registration, status changes
  (including close/reopen), accepted loss advice, reserve changes, cash calls, recoveries,
  commutations, task lifecycle changes, and notes write their canonical event in the aggregate
  transaction. Quartz schedules dispatch only when `eia.outbox.enabled=true`.
- JUnit invariants/controllers, H2 outbox transaction/payload tests, and live PostgreSQL smoke
  test (`liveTest`).
