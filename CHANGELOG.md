# Changelog

All notable changes to this repository are documented here.

The format is based on Keep a Changelog and the project uses Semantic Versioning.

## [Unreleased]

### Added

- Repository baseline.
- Spring Boot 3.4.0 expert slice: `assumed_claim` schema, internal list/get/create/status endpoints, ASSUMED book guard, structured errors, and JUnit controller and lifecycle tests.
- Second slice: loss advice and bordereau ingestion with duplicate detection, append-only reserve history with authority rules, cash calls, recoveries, commutation, disputes, financial projection, and append-only claim audit. Includes new service/controller tests and a live PostgreSQL smoke test (`liveTest`, `EIA_LIVE_POSTGRES=true`).
