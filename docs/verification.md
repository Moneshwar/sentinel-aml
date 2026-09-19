# Verification — 19 September 2026

## Current high-level review

The current implementation includes durable asynchronous CSV/JSON imports, a single background
worker, progress polling, and synchronous detection within each processed transaction. Kafka and
Redis remain outside scope. See [async ingestion](async-ingestion.md) for recovery and deployment limits.

Checks rerun for this documentation and implementation review:

- `./gradlew test`: **125 tests across 16 suites, zero failures/errors/skips** (H2 test database).
- `npm run build` and `npm run lint` in `frontend/`: **passed**.
- Source review covered the bulk-job/controller path, detection integration and access controls.

This was a high-level review. PostgreSQL migrations, live API demonstrations, browser interaction,
and performance were not rerun. The results below are retained historical evidence; the benchmark
predates the asynchronous bulk implementation. Current demo fixtures contain 13 customers,
13 accounts and 129 transactions; the older live PostgreSQL run used 5/5/100.

## Historical verification before asynchronous imports

The following results describe the earlier implementation and its verification environment.

## Automated checks

`./gradlew test` passed **116 tests** across 13 suites:

- 30 pure rule tests and 10 historical-FX tests.
- 8 engine orchestration tests and 8 complete detection-pipeline integration tests.
- 13 security/workflow tests and 5 queue-filter regressions.
- 35 ingestion/parser/API/startup checks, including streamed CSV recovery, per-record rollback and error caps.
- 7 alert regeneration checks: administrator authorization and confirmation, current rule thresholds/enablement/scores, historical FX, retained source data and audit, case-link removal, repeated rebuilds, rollback on failure and pagination across equal timestamps.

Tests cover all six rules, threshold/window boundaries, configuration changes, both behavioral count/value paths, USD-equivalent thresholds, mixed-currency rate changes, mandatory controls, weighted scoring, evidence aggregation, late deposits, disposed findings, concurrent replay, API authorization, PII masking, server-derived audit identity and required disposition reasons.

Frontend `npm run build` (TypeScript + Vite) and `npm run lint` passed. The OpenAPI YAML parses and internal schema references resolve.

## Actual PostgreSQL checks

A separate PostgreSQL 16.14 container and database were used on localhost port 15432, with generated temporary passwords. Existing PostgreSQL/Redis containers and user data were not used. Temporary verification servers and the temporary database container were removed after the checks.

- Flyway migrations V2–V7 and Hibernate schema validation passed.
- Direct attempts to UPDATE, DELETE and TRUNCATE audit history were rejected by the append-only database triggers.
- `scripts/demo.py` passed: 5 synthetic customers, 5 accounts and 100 transactions produced all six expected rule findings with correct scores, evidence and explanations. The demo verified stream replay, case creation/customer inference, required disposition reasons, terminal-state protection and authenticated audit identity despite a forged actor field.
- The three real multipart CSV endpoints accepted the provided 5 customer / 5 account / 100 transaction fixtures without rejected records.
- Eight concurrent HTTP stream requests with one external transaction reference produced **one transaction and one alert**.
- Actual VIEWER requests verified masked customer list responses and rejection of sensitive detail/configuration access.

## Alert regeneration follow-up

An additional isolated PostgreSQL 16 container on port 15433 and app on port 18081 verified
Flyway/schema validation, rebuild table locks, saved CTR threshold and score changes, case-link
removal and repeated rebuilds with stable alert counts. The two stored transactions were retained.
These temporary services were removed afterward; the user database was not rebuilt.
The frontend build and lint passed after adding the confirmation panel.

## Measured performance

`scripts/benchmark.py` ran on the local service after the demo warm-up, using PostgreSQL and authenticated HTTP requests. All six rules were enabled.

| Measurement | Result |
| --- | --- |
| Bulk transactions ingested and evaluated | 10,000 |
| Customers / accounts | 100 / 100 |
| Request shape | 100 JSON batches of 100 records |
| Flagged workload | 10% high-risk-jurisdiction transactions |
| Persisted aggregated alerts | 100 |
| Bulk elapsed time | **53.178 seconds** (target below 120 seconds) |
| Individual streaming samples | 10 |
| Streaming median | **73.319 ms** |
| Streaming maximum | **80.967 ms** |

Raw measurements are in [benchmark-result.json](benchmark-result.json). These are local measurements for this workload, not a universal latency guarantee. Large historical backfills and highly concentrated customer histories can take longer.

## Frontend and packaging

The frontend contains authenticated role-based navigation, alert evidence and disposition, case management, customer/account timelines, CSV imports, individual transaction entry, administrator rule/FX/risk-list editors, and paginated authenticated audit history. The versioned frontend source is in `frontend/`; the earlier verification also checked a separate local copy.

Authenticated HTTP checks through the Vite server in the requested frontend folder passed for all queue/detail/configuration/audit routes and numeric customer lookup. Synced frontend source checksums match.

A local Git repository on `main` is initialized and source files are staged. No commit or remote publication is claimed. Passwords, dependency directories, build outputs and local caches are excluded.

No connected browser was available, so rendered appearance and interactive browser workflows were **not visually verified**. Production build, lint, authenticated API workflows and HTTP proxy checks do not substitute for browser end-to-end tests.

See [README](../README.md) for environment/password setup, startup and reproducible demo commands. Earlier audit findings are retained as clearly marked historical baselines in the two audit documents.
