# eia-assumed-claims-expert

- GitHub organization: `eia-open-insurance`
- Repository URL: https://github.com/eia-open-insurance/eia-assumed-claims-expert
- Type: Spring Boot service
- Deployable: Yes
- Owner: Assumed Claims
- Runtime: Java 22, Spring Boot, Spring Batch, Quartz
- Data store: eia_assumed (ent, ref, audit schemas)
- Depends on: eia-contracts, eia-platform/libs

## Purpose

Assumed Claims persistence, invariants, read models, batch jobs, Quartz schedules, and outbox/inbox. Assumed claims: loss advices, reserves, cash calls, recoveries, and commutations.

## Responsibilities

- Keep this repository focused on one deployable component or one platform concern.
- Follow the repository standard in `../CONVENTIONS.md` and the specification in `../../SPEC.md`.
- Publish and consume contracts through `eia-contracts`.
- Maintain README, CODEOWNERS, SECURITY, CHANGELOG, tests, and local verification.
- Contain no business logic in shared libraries or front-end remotes.

## Implemented slice

- Spring Boot 3.4.0 on Java 22, listening on port `8089`, database `eia_assumed`.
- Tables: `assumed_claim`, `assumed_loss_advice`, `assumed_bordereau`,
  `assumed_reserve_history`, `assumed_cash_call`, `assumed_recovery`, `assumed_commutation`,
  `assumed_dispute`, and the append-only `assumed_claim_audit`. All DDL in
  `schema.sql` is idempotent, and an UPDATE/DELETE on the audit table is refused by a database
  trigger.
- Internal API under `/internal/v1/assumed-claims`:
  - claims: list, get, create, `POST /{id}/status`, `POST /{id}/close`,
    `GET /{id}/financials`, `GET /{id}/audit`;
  - advice: `GET|POST /{id}/advices`, `POST /{id}/advices/{adviceId}/accept`,
    `GET|POST /{id}/bordereaux`;
  - reserves: `GET|POST /{id}/reserves`;
  - cash: `GET|POST /{id}/cash-calls`;
  - recoveries: `GET|POST /{id}/recoveries`;
  - commutation: `GET /{id}/commutation`, `POST /{id}/commute`;
  - disputes: `GET|POST /{id}/disputes`, `POST /{id}/disputes/{disputeId}/resolve`.
- The book wall is enforced on every `/internal/v1/**` call. A request without
  `X-EIA-Book: ASSUMED` receives a structured `404` (`EIA-RNS-7005`), never `403`.
- Invariants enforced transactionally:
  - advice numbers are unique per claim, and a claim cannot be opened without a `POSTED`
    (accepted) advice; bordereau batches are idempotent by reference and content hash and report
    duplicate lines instead of overwriting them (RNS-02);
  - reserves are append-only history; the current reserve is the sum of the current component rows.
    Negative amounts, stale effective dates, and missing reasons are rejected; IBNR and large
    movements require actuarial/senior/underwriting authority and a named approver (RNS-04/05);
  - a cash call cannot exceed the current reserve net of outstanding cash calls (RNS-09);
  - a recovery cannot exceed the ceded advised/paid loss, including prior recoveries (RNS-10);
  - commutation requires a named claims or underwriter approver plus a signed release; an open
    dispute or an unresolved advice blocks it, and execution releases current reserves and closes
    the claim (RNS-08/11);
  - closure is blocked by an open dispute, a current reserve, an unresolved advice, or an
    outstanding cash call (RNS-11/12).
- Money is stored as `numeric(19,4)` and serialized as a decimal string; every money-bearing DTO
  normalizes to four fractional digits.
- Errors are structured `Problem`-shaped JSON for `400`, `404`, `409`, `422`, and `428` cases.

## Local run

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-22.0.2.9-hotspot'
.\gradlew.bat build
java -jar build\libs\eia-assumed-claims-expert-0.1.0-SNAPSHOT.jar
```

Create the database once with
`psql -U postgres -h localhost -c "CREATE DATABASE eia_assumed"`. Schema bootstrap runs from
`schema.sql` on startup.

## Verification

- Unit and controller tests: `.\gradlew.bat test` (105 tests).
- Live PostgreSQL smoke: creates a dedicated `eia_assumed_live` database and exercises
  advice -> reserve -> cash call -> recovery, the open gate, recovery cap, append-only audit,
  dispute blocking, commutation closure, and bordereau duplicate/replay:

```powershell
$env:EIA_LIVE_POSTGRES='true'
.\gradlew.bat liveTest
```

## Notes

Writes data; does not decide business outcomes.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
