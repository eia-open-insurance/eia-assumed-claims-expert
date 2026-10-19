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
- Table `assumed_claim` with `id` (UUIDv7), `tenant_id`, `business_book` fixed to `ASSUMED`,
  `assumed_claim_number` (`RNS-YYYY-NNNNNNN`), `contract_id`, `cedent_claim_number`, `status`,
  `loss_date`, `report_date`, `reserve_amount`, `reserve_currency`, `version`, `created_at`, and
  `updated_at`. Check constraints enforce `report_date >= loss_date`, non-negative reserves, and
  the ASSUMED book.
- Internal API: `GET /internal/v1/assumed-claims`, `GET /internal/v1/assumed-claims/{id}`,
  `POST /internal/v1/assumed-claims`, and `POST /internal/v1/assumed-claims/{id}/status`.
- The book wall is enforced on every `/internal/v1/**` call. A request without
  `X-EIA-Book: ASSUMED` receives a structured `404` (`EIA-RNS-7005`), never `403`.
- The status lifecycle from SPEC §11.5.6 is enforced with optimistic `version` concurrency.
- Errors are structured `Problem`-shaped JSON for `400`, `404`, `409`, and `415`/`405` cases.

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

- Lint and format
- Unit tests with 100% business-logic coverage where applicable
- Contract tests
- Integration or Playwright tests where applicable
- Security scan and SBOM
- Build and publish

## Notes

Writes data; does not decide business outcomes.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
