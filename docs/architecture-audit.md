# Sentinel AML architecture audit — current implementation

**Verification scope:** Test counts, PostgreSQL checks and benchmark figures below are historical. See [verification.md](verification.md) for the latest checks (125 backend tests and frontend build/lint) and their limits. The current migration chain includes V8 for asynchronous jobs.

**Async ingestion update:** CSV and JSON batch imports now use durable PostgreSQL jobs, return 202, and expose progress polling. Earlier exclusions and missing-job findings below are historical; see [the current contract](async-ingestion.md).

**The initial architecture audit below is superseded.** Its missing-engine/security findings describe the starting repository, not the current implementation. The user's subsequent scope excludes **Kafka, Redis and remaining Extension Ideas**; their absence is not an outstanding requirement in the agreed final scope.

## Current processing architecture

```text
CSV upload / JSON batch / continuous transaction REST
  → parse and validate each record
  → begin a PostgreSQL transaction and lock the customer
  → validate replay identity and normalize transaction-time currency
  → persist the transaction
  → build historical detection context
  → evaluate discovered DetectionRule strategies
  → aggregate scores, explanations and transaction evidence
  → create or merge a durable alert and append audit history
  → commit transaction + detection output together
  → authenticated analyst UI → case/disposition workflow
```

This is a **PostgreSQL-backed prototype with asynchronous bulk imports**. Single-transaction requests wait for detection; CSV/batch requests return a job for background processing and progress polling. No broker or cache is required. The previous in-memory event abstraction remains legacy scaffolding; the active transaction ingest path calls the engine atomically.

| Architectural responsibility | Current implementation |
|---|---|
| Durable source of truth | PostgreSQL stores customer/account/transaction data, alert evidence, cases, audit, rule settings, sanctions and effective-dated FX. Flyway V2–V7 and Hibernate validation have been exercised against PostgreSQL. |
| Bounded CSV parsing | `CsvReader.forEach` emits individual records, validates schema headers and reports malformed rows. Import services cap in-memory returned errors while persisting/counting every rejection; master-data rows have transactional upserts. |
| Shared detection path | CSV, JSON batch and single/streaming transaction calls converge on `TransactionIngestionService.ingest` and the same `DetectionEngine`. |
| Extensible rule strategies | Spring injects `List<DetectionRule>`; six independent implementations return `DetectionResult`. The engine builds context and handles persistence, so rule implementations do not create alerts or cases. |
| Historical context and event time | Configured windows control context; history is bounded to each evaluation timestamp. Late arrivals replay affected later anchors. FX and high-risk jurisdictions respect effective dates. |
| Risk and explainability | The engine combines rule weights, caps scores at 100, merges transaction evidence and writes readable per-rule explanations. |
| Idempotency and concurrency | Unique external references, replay-payload comparison and customer-level PostgreSQL locking protect cross-account customer rules. Findings merge into an open customer/day alert. Reviewed findings are preserved and additional evidence can create a new review. |
| Atomic durability | Transaction + detection output + audit commit together. A detection failure rolls back acceptance; there is no broker publication gap in this synchronous design. |
| Analyst updates and audit | Role-protected alert/case workflows require disposition reasons, derive actor identity from authentication and disallow terminal rewrites. Optimistic version columns detect competing analyst edits. PostgreSQL triggers reject audit updates, deletes and truncation. |
| Runtime administration | ADMIN-only rule/FX/jurisdiction/counterparty APIs; validated settings are read for subsequent evaluation without application redeployment. |
| Minimal frontend | React application consumes authenticated APIs for queue, page-level risk distribution, evidence, customer timeline, case handling, audit and administration; it does not simulate excluded infrastructure. |

Source paths are under `src/main/java/com/moneshwar/hackathon/`; the precise functional/business-rule mapping is in [requirements-audit.md](requirements-audit.md).

## Verification boundary

The coordinating run reports **109 passing backend tests**, successful **PostgreSQL V2–V7 migration plus Hibernate schema validation**, and verified rejection of **audit UPDATE/DELETE/TRUNCATE**. The PostgreSQL demonstration also passed for all six rules, replay, disposition and authenticated audit identity; frontend build and lint passed. The 10,000-transaction PostgreSQL benchmark completed in **53.178 seconds**, with ten streaming samples at **73.319 ms median / 80.967 ms maximum**; workload and limits are recorded in [verification.md](verification.md). No connected browser is available: no visual or interactive-browser verification is claimed.

The original large-scale distributed architecture is intentionally not claimed. Customer-level database serialization gives correctness within the current prototype but does not itself demonstrate horizontal throughput or large-history performance; those conclusions require measurements.

<details>
<summary>Historical initial architecture audit — superseded scope and findings</summary>

# Sentinel AML architecture audit

Reviewed on 2026-09-19 against `Sentinel_AML_System_Flow_and_Detection_Engine.md`. This records the backend present at the start of this review; concurrent frontend work is outside this audit. Original challenge extension ideas are excluded. Kafka and Redis remain in scope because the supplied architecture explicitly requires them.

**The requested architecture is not implemented end to end.** The repository contains a persistence and ingestion foundation, analyst workflow endpoints, and the beginning of a rule strategy interface. It cannot currently produce AML alerts from incoming transactions: no detection worker or engine consumes the published transaction events.

## Architecture coverage

| Requested component | Observed status | Evidence |
| --- | --- | --- |
| PostgreSQL as source of truth | Implemented foundation | `docker-compose.yml`, `application.properties`, `db/bootstrap/V2__core_domain.sql`; entities and repositories cover customers, accounts, transactions, alerts, cases, audit, exchange rates. V5 adds rule configuration and jurisdiction tables. |
| CSV validation, normalization, rejected rows | Implemented | `service/ingestion/TransactionIngestionService.java`, `CurrencyNormalizer.java`, `RecordValidator.java`, `IngestionErrorRecorder.java`. Customer and account CSV paths also exist. |
| Immediate upload response with job ID; polling progress | Missing | `controller/IngestionController.java` directly calls `importCsv` and returns the completed `IngestionResult`. No ingestion job entity, job manager, worker queue, or job polling endpoint exists. |
| Streaming or bounded batches for large CSVs | Missing | `service/ingestion/CsvReader.java#read` accumulates every parsed row into a list. Import services process that list sequentially, retaining errors until the result is capped. |
| Continuous individual transaction API | Implemented entry point | `controller/TransactionController.java#stream` returns 201 for a new reference and 200 for a sequential replay. Both CSV and API paths call `TransactionEventPublisher`. |
| Kafka decoupling and detection workers | Missing | `stream/InMemoryTransactionEventPublisher.java` calls Spring `ApplicationEventPublisher.publishEvent`. No Kafka dependency, broker service, producer, listener, consumer group, retry, or dead-letter configuration exists. No application event listener consumes these events either. |
| Redis detection windows, aggregates, deduplication | Missing | No Redis dependency, service, client, cache implementation, TTL, or context rebuild path exists. |
| Extensible rule interface and result type | Partially implemented | `detection/DetectionRule.java`, `DetectionResult.java`, `RuleConfiguration.java`, `TransactionContext.java` define the desired separation. Only `rules/CtrRule.java` implements a rule. No engine or registry discovers or executes rules. |
| Five required AML typologies | Missing execution | Structuring, rapid movement, high-risk jurisdiction, behavioral deviation, and round-number rules have seed configuration in V5 but no Java implementations. CTR is an additional scaffold and is not wired into transaction processing. |
| Risk aggregation and explainable supporting evidence | Partial data structures only | `DetectionResult` carries score, explanation, and transaction references. Alert entities support evidence links. No risk scoring service or rule-result aggregation exists. |
| Runtime rule configuration | Partially implemented | `entity/RuleConfig.java`, `repository/RuleConfigRepository.java`, and V5 store JSON settings. No configuration API or executing engine reads them. Invalid JSON silently becomes an empty configuration. |
| Configurable high-risk jurisdictions | Partially implemented | Entity, repository, and seed rows exist. No rule evaluates them and no management API exists. Repository queries only check `effectiveTo IS NULL`, not full effective-date validity. |
| Durable transaction uniqueness | Implemented | `Transaction.transactionRef` and V2 `transactions.transaction_ref` have unique constraints. Sequential stream replays return the existing record. |
| Detection idempotency | Missing | No durable processed-event ledger, worker acknowledgement strategy, or Redis idempotency state exists. Ingestion uniqueness is not a substitute for idempotent alert processing. |
| Alert creation/update/deduplication | Missing detection integration | `AlertService` lists, retrieves, and changes status only. `AlertRepository#existsByDedupKey` is unused; the V2 dedup index is nonunique. |
| Analyst case workflow and audit | Implemented foundation | `CaseService` creates cases, links alerts, updates status/disposition, and records audits. `AlertService#updateStatus` records audits. No automatic alert creation audit can occur because detection is absent. |
| Same-account event ordering and horizontal workers | Missing | No account-keyed partitioning, consumer concurrency policy, shared claim/lock, or version check exists. |

Paths under `service`, `controller`, `detection`, `entity`, `repository`, and `stream` are relative to `src/main/java/com/moneshwar/hackathon/`. SQL and properties paths are relative to `src/main/resources/`.

## Correctness findings

1. **No durable handoff from persisted transactions to detection.** In API `TransactionIngestionService#ingest` and `#ingestStreamingWithOutcome`, event publication happens inside the transaction, before commit. A future asynchronous consumer can observe an event before its database row is visible, or observe an event whose database transaction subsequently rolls back. In CSV/batch `#ingestCached`, there is no surrounding service transaction: repository save commits separately before event publication, so a process failure between those calls loses the event. The in-memory publisher also loses all pending work on restart. Persist an outbox event atomically with the transaction, then publish it with retry; use idempotent consumption to handle duplicates.

2. **Stream replay is sequentially idempotent, not race-safe.** `#ingestStreamingWithOutcome` checks for an existing reference and then inserts. Two simultaneous requests can both pass the check; the unique constraint prevents duplicate storage, but the loser gets a constraint conflict instead of the documented replay response. Handle a unique-key race outside the failed transaction and load the winning row in a fresh transaction, or use a database-supported atomic insert-or-load approach. The current replay path also does not detect conflicting payloads sharing a reference; define that behavior explicitly.

3. **Deduplication has no durable uniqueness boundary.** The `dedup_key` field and existence query do not prevent two workers from creating related alerts concurrently. Define the account/customer, rule grouping, time window, and handling of closed alerts; enforce the resulting identity in PostgreSQL. Use a unique key or atomic locked update, keep evidence merging and risk updates in the same transaction, and treat Redis as acceleration only.

4. **Customer-wide rules need additional concurrency design.** Account-keyed Kafka events preserve delivery order per account, but behavioral deviation is customer-wide. Two accounts belonging to one customer can update shared aggregates or the same alert concurrently. Use atomic shared-state updates and durable alert conflict handling; alternatively route customer-scoped work by customer ID. Partition order also does not guarantee event-time order for delayed or backfilled transactions: define late-arrival evaluation and replay behavior.

5. **Configuration currently cannot change real behavior.** V5 seeds settings and adds `alerts.triggered_rules`, `disposition`, and `disposition_reason`, but the current `Alert` entity/response do not map these columns. `TransactionContext` exposes fixed 24-hour, 48-hour, and 90-day lists despite the desired configurable windows. A future context loader must honor configured lookback periods and exclude future transactions from historical evaluations.

6. **Monetary thresholds need explicit currency semantics.** Normalization defaults to INR, and CTR/structuring seeds use 10,000 and 9,000–9,999 in that base currency. They do not represent a USD 10,000 / 9,000–9,999 threshold after conversion. The migration comment suggests manual production adjustment; that does not establish equivalence for the challenge's dollar examples. Define rule threshold currency and convert consistently, or clearly use base-currency thresholds in rule configuration and UI.

7. **Fixed during this review: API status filtering had a type mismatch.** Runtime requests for `GET /alerts?status=OPEN` and `GET /cases?status=OPEN` returned HTTP 500 because repository parameters were strings while entity status attributes were enums. Repository filters now accept `AlertStatus`/`CaseStatus`; services trim and parse case-insensitively using `Locale.ROOT`, and unknown statuses reach the existing HTTP 400 handler. Filtered alert lists now retain descending risk ordering before pagination. `AnalystStatusFilterIntegrationTest` covers valid/invalid filters, matching fixtures, and risk ordering.

## Prioritized implementation sequence

1. **Make the pipeline produce correct results:** implement all required independent rules, a registry populated by Spring, a context loader, explicit risk scoring capped at 100, alert create/update with evidence and explanations, and automatic audit entries. Keep rule evaluation deterministic within each event.
2. **Make accepted data recoverable:** introduce transactional outbox records and durable detection processing state; ensure a transaction, its emitted work, and eventual idempotent results survive application restarts. Add unique alert identity and safe concurrent updates before scaling consumers.
3. **Implement the specified infrastructure:** Kafka topic and producer keyed by the chosen ordering key, consumer group with bounded concurrency, retries and dead-letter handling; Redis windows and aggregates with TTLs and PostgreSQL fallback/rebuild. Add local services and connection configuration.
4. **Introduce asynchronous ingestion jobs:** persist upload/job metadata, return HTTP 202 with a job ID, process records incrementally in bounded batches, persist counts/status/errors, expose a polling endpoint, and define recovery for interrupted jobs. Include alert progress only when it can be correlated reliably with the ingestion job.
5. **Complete runtime administration and analyst integration:** validated rule configuration and jurisdiction management APIs, full triggered-rule data on alert responses, case/alert status filtering, and a minimal frontend for uploads, progress, ranked alerts, evidence, and cases.

## Verification needed before calling the architecture complete

- Each typology triggers on its intended positive example and stays quiet at threshold/time-window boundaries; configuration changes affect subsequent evaluations without restart.
- Both CSV and individual transaction ingestion reach the same engine and produce persisted alerts containing score, rules, evidence, and explanation.
- An upload returns before CSV processing completes; progress converges to persisted accepted/rejected totals without loading the whole CSV into memory.
- Restart between transaction commit and Kafka publish does not lose work. Restart after alert persistence but before consumer acknowledgement does not duplicate alerts or audit events.
- Concurrent duplicate transactions return a consistent replay result; concurrent same-pattern detections produce one merged alert without lost evidence.
- Same-account ordering, customer-wide aggregates, out-of-order timestamps, Redis eviction, and broker/cache outages have defined and tested behavior.

Existing `IngestionIntegrationTest` and `IngestionApiIntegrationTest` cover ingestion, validation, currency normalization, error reporting, and sequential replay. They do not establish detection, job, broker/cache, restart, or concurrent-processing correctness. This audit is based on source inspection; it does not claim the existing test suite was executed.

</details>
