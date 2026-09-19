# Sentinel AML requirement audit — current implementation

**Verification scope:** Test counts, PostgreSQL checks and benchmark figures below are historical. See [verification.md](verification.md) for the latest checks (125 backend tests and frontend build/lint) and their limits. The current migration chain includes V8 for asynchronous jobs.

**Async ingestion update:** CSV and JSON batch imports now use durable PostgreSQL jobs, return 202, and expose progress polling. Earlier exclusions and missing-job findings below are historical; see [the current contract](async-ingestion.md).

**The initial audit below is superseded.** The ingestion-only baseline has been extended into a working detection and analyst-workflow implementation. This section describes the current source; the collapsed historical audit records what was missing before the implementation work.

Current scope follows the user's clarification: **remaining Extension Ideas, Kafka and Redis are excluded**. CSV and batch imports now process as background jobs with persisted progress. The implementation does not claim the excluded distributed architecture.

## Current core coverage

| Area | Current implementation |
|---|---|
| Part A — data model and ingestion | Customer/account/transaction/alert/case relationships, account risk rating, PostgreSQL migrations, REST and JSON batches, streaming CSV parsing, continuous transaction endpoint, validation and referential checks. CSV errors are persisted, failure counts remain exact, and responses retain at most 200 error details. Malformed rows do not prevent later valid rows from being accepted when parsing can safely continue. |
| Part B — detection | CTR plus structuring, rapid movement, high-risk jurisdiction/counterparty sanctions, behavioral deviation and repeated round-number rules. Spring discovers independent `DetectionRule` strategies; `DetectionEngine` builds historical context, aggregates results and persists scored alerts with evidence and explanations. |
| Runtime configuration | ADMIN-only rule, exchange-rate, jurisdiction and sanctioned-counterparty APIs. Thresholds, windows and rule weights are validated; configuration changes affect subsequent evaluation without redeployment. Mandatory CTR/sanctions review cannot be disabled. |
| Alert aggregation and concurrency | PostgreSQL customer locks serialize competing customer streams, external transaction references are unique, replay payloads are checked, and open daily customer findings merge rules/evidence. Transaction persistence, detection and its audit entries commit atomically. Optimistic versions protect analyst updates. |
| Part C — minimal frontend | React source implements login, ranked alerts, page-scoped risk distribution, detail/evidence views, customer account timelines, cases/dispositions, audit history, imports, transaction entry and administration. Backend RBAC is enforced independently of UI visibility. Current browser/build/demo results belong in [verification.md](verification.md). |
| Supporting deliverables | OpenAPI specification at [openapi.yaml](openapi.yaml), synthetic demonstration data at [demo-data/README.md](demo-data/README.md), schema/migrations, setup/architecture README and detection/security/ingestion tests. A local Git repository on `main` is initialized; source staging is complete, with no commit or remote publication claimed. Final performance results belong in the verification record. |

## The nine business rules

| # | Requirement | Current coverage and evidence |
|---|---|---|
| 1 | Any transaction at least USD 10,000 equivalent is flagged | `detection/rules/CtrRule.java`, `RuleSupport`, `FxRateTimeline` and configured USD threshold currency. Detection executes inside transaction ingestion. |
| 2 | At least three USD 9,000–9,999 equivalent transactions from one account within 24h | `detection/rules/StructuringRule.java`; configurable count, bounds and lookback, with transaction-time currency conversion. |
| 3 | At least 80% of a deposit transferred out within 48h | `detection/rules/RapidMovementRule.java`; sums subsequent outflows against a specific qualifying deposit, excludes earlier outflows and includes supporting references. This is temporal pattern detection, not tracing of particular banknotes/funds. |
| 4 | Configured high-risk jurisdictions or sanctioned counterparties always reviewed | `detection/rules/HighRiskJurisdictionRule.java`, effective-dated jurisdiction lookup and configurable counterparty identifiers; mandatory regardless of amount. |
| 5 | Daily count/value above 3× the preceding 90-day daily average | `detection/rules/BehavioralDeviationRule.java`; customer-wide count and value across accounts, complete UTC days including zero-activity days, excluding today. With no historical observations the rule waits for a usable baseline. |
| 6 | Cleared alerts retained with reason and analyst identity | `service/AlertService.java`, `security/AuditActor.java`, `AuditLogService`: required disposition reason, authenticated actor, retained alert and audit timestamp, terminal-state protection; no delete API. |
| 7 | Weighted risk 0–100 and highest-risk-first queue | `detection/DetectionEngine.java` aggregates rule weights and caps risk at 100; filtered and unfiltered queues order by risk before pagination. |
| 8 | PII masked in lists; full details restricted to authorized roles | Customer names/identifiers/contact fields, counterparty fields and free-text narratives are suppressed in list/restricted responses. ADMIN/ANALYST may read full personal details; VIEWER cannot. `security/SecurityConfiguration.java`, mapper list methods and privacy-aware detail services enforce this in the API. |
| 9 | Amounts normalized to configurable base currency and exchange rates | `service/ingestion/CurrencyNormalizer.java` selects rates effective at transaction time, stores base amounts and rejects missing rates. Threshold comparisons honor configured threshold currency and historical FX. |

Java paths in these tables are relative to `src/main/java/com/moneshwar/hackathon/`.

## Verification status and remaining checks

- **109 backend tests pass**, including rule boundaries, detection integration, replay/concurrency, security/workflows and streamed CSV behavior, as reported by the coordinating verification run.
- **PostgreSQL Flyway V2–V7 migrations and Hibernate schema validation passed** against the verification database.
- **PostgreSQL audit UPDATE, DELETE and TRUNCATE attempts were all rejected**, verifying the append-only migration triggers in the actual target database.
- The **PostgreSQL demonstration passed for all six rules**, case/alert disposition, authenticated audit identity, streaming replay and authorized numeric customer lookup, as reported by the coordinating run.
- The **frontend production build and lint checks passed**. No connected browser is available, so rendered layout and interactive browser behavior are **not visually verified**.
- The **10,000-transaction benchmark passed in 53.178 seconds**. Ten streaming samples measured a 73.319 ms median and 80.967 ms maximum. See [verification.md](verification.md) for workload details and limitations.
- A **local Git repository on `main` is initialized and sources are staged**. No commit or remote publication is claimed.

No additional confirmed core-logic defect was found in this final source pass. Passing tests and schema checks do not establish unmeasured performance or a visually verified browser workflow.

<details>
<summary>Historical initial audit — superseded findings, retained for traceability</summary>

# Sentinel AML requirement audit

Reviewed 19 September 2026 against the supplied challenge specification, excluding **Extension Ideas**, and `Sentinel_AML_System_Flow_and_Detection_Engine.md`. This is a source-code audit of the backend at the start of the frontend task; subsequent changes should be checked against this baseline. Paths below are relative to the backend repository, and Java paths abbreviated as `java/…` mean `src/main/java/com/moneshwar/hackathon/…`.

**Answer: the non-extension requirements are not all implemented.** The ingestion and relational-data foundation exists, with basic alert/case APIs. The operational detection pipeline and five requested typologies are missing. A standalone CTR rule and configuration tables are scaffolding, not a working ingestion → detection → alert flow. The README itself describes the implementation as Part A only.

## Functional coverage

| Requirement | Status | Source evidence and qualification |
|---|---|---|
| Java 17+, Spring Boot, JPA, relational database | Implemented, except mandated Security component | `build.gradle` uses Java 21, Spring Boot, Web, JPA, validation, PostgreSQL and Flyway; no Spring Security dependency. |
| Customer → account → transaction, alert and case relationships | Implemented | `entity/` and `src/main/resources/db/bootstrap/V2__core_domain.sql`; foreign keys and join tables exist. |
| Account metadata including risk rating | Partial | Account type, currency and dates exist in `entity/Account.java`; risk rating exists on Customer only, not Account. |
| REST, JSON batch and CSV ingestion | Implemented | `java/controller/IngestionController.java:46`, `TransactionController.java:41`, corresponding customer/account controllers and ingestion services. |
| Validation, referential integrity, ingestion error records | Mostly implemented | Bean-validation DTOs, `RecordValidator`, CSV parsers, foreign-key resolution and per-row error recording. Single REST validation/rejection paths return errors without persisting ingestion-error records; error consistency caveat below. |
| Continuous/incremental transaction REST endpoint | Implemented at ingestion level | `TransactionController.java:56` and `TransactionIngestionService.java:92` accept one transaction, return 201 for creation and 200 for an already-persisted reference. Sequential replay suppression exists. |
| CTR: automatically flag USD 10,000 equivalent | Scaffold only; incorrect default units | `java/detection/rules/CtrRule.java:39` calculates a result but nothing invokes it to create an alert. Default INR 10,000 threshold is not USD 10,000 equivalent. |
| Structuring: ≥3 amounts USD 9,000–9,999 in 24h | Missing | Only configuration seed exists in `V5__detection_engine.sql`; no structuring rule implementation. |
| Rapid movement: ≥80% transferred within 48h | Missing | Configuration seed only; no rapid-movement implementation. |
| High-risk jurisdiction / counterparty sanctions | Missing | `HighRiskJurisdiction` entity, repository and seeded country table exist; no evaluation rule, active-list lookup service or counterparty sanctions model. |
| Behavioral deviation: daily count/value >3×90-day average | Missing | Context record and configuration fields exist; no aggregation/context-loading or behavioral rule implementation. |
| Repeated round amounts / just-below-threshold pattern | Missing | ROUND_NUMBER configuration seed only. |
| Rule registry and engine, common results | Partial scaffold | `DetectionRule`, `DetectionResult`, `TransactionContext`, `RuleConfiguration`, `CtrRule` exist. There is no `DetectionEngine`, registry, context loader or subscribed worker. |
| Runtime enable/disable/tuning without redeploy | Partial scaffold | `RuleConfig` and `V5` store JSON settings; no service reads them into a running engine, and no admin API exists. |
| Alerts with risk, triggered rules, evidence and explanation | Schema/DTO foundation only | Alert entity and evidence join table exist; `AlertService` only lists, gets and updates status. No ingestion path creates alerts. |
| Combined weighted risk score capped at 100 | Missing | No scoring service/aggregation; risk score is a stored field only. |
| Risk-sorted analyst queue | Implemented for stored alerts | Both unfiltered and status-filtered queries order by risk before pagination; the status-filter defect was fixed and regression-tested during this task. Automated alert generation is still missing. |
| Alert deduplication/aggregation | Missing | `dedup_key` and `existsByDedupKey` exist, but no deduplication workflow; SQL index is nonunique. |
| Case workflow | Basic implementation | `java/service/CaseService.java:62` creates cases with linked alerts and `:93` updates status/disposition. No enforced transition graph; closing can omit disposition reason. |
| Preserve cleared alerts with reason and analyst identity | Partial / fails required disposition | No delete endpoint exists. `AlertStatusUpdateRequest` has no disposition/reason field and `AlertService.java:50` writes null audit details. Actor is supplied by the caller, not verified identity. |
| Mask PII in list views; authorize full detail | Missing | Customer lists and details use the same unmasked mapper/response (`CustomerService.java:35`, `CustomerResponse`). No API-layer RBAC. |
| Normalize every amount using configurable FX table | Partial | `CurrencyNormalizer.java:30` handles configured pairs; missing rates silently leave `amountBase` null, permitting unnormalized data. |
| Immutable audit history | Partial | State updates append `AuditLog` rows in the same transaction; actor is untrusted input, entity is mutable, repository exposes normal update/delete methods, and SQL has no append-only enforcement. |
| Minimal frontend/dashboard | Separate implementation task | Backend exposes data to support basic views; no frontend was present in this backend audit baseline. A UI does not supply the missing detection/security pipeline. |

## Requested architecture coverage

Kafka and Redis are included here because the user explicitly supplied them as the desired architecture, even though Kafka streaming was an extension in the original challenge.

| Architectural element | Current state |
|---|---|
| CSV POST immediately creates a queued job and returns job ID | Missing. CSV controllers synchronously call import services and return final counts. |
| Job manager, durable job state, progress polling endpoint | Missing. Batch IDs identify completed import results/errors; they are not jobs. |
| Bounded/background ingestion worker, incremental file parsing | Missing. `CsvReader.java:24` materializes every row into a list before ingestion. |
| Persist transactions, then durably publish events | Partial. Event abstraction exists, but REST paths publish inside the transaction before commit; no outbox/recovery. |
| Kafka broker, publisher, consumer, retry/dead-letter handling | Missing. `InMemoryTransactionEventPublisher` uses Spring application events; no Kafka dependency or broker in Compose. |
| Account-keyed partitions and concurrent detection workers | Missing. No worker/partitioning implementation. |
| Redis recent windows, aggregate/context cache, idempotency/deduplication | Missing. No Redis dependency, configuration or implementation. |
| PostgreSQL durable system of record | Implemented for current entities/configuration; no job/outbox/processed-event records. |
| Bulk and real-time ingestion converge on detection | Event hook exists for both, but there is no detection consumer. |
| Pure rule strategies returning common results | Initial scaffold fits the requested direction; only CTR exists. |
| Engine → scoring → deduplicating alert manager → audit | Missing operational flow. Existing alert/case status audit is separate. |

## Priority correctness and security findings

1. **No generated alerts:** `TransactionIngestionService.java:79`, `:106`, `:210` publish events, but the source tree has no event listener, worker or engine. Loading suspicious sample data cannot currently populate the queue automatically.
2. **Public sensitive APIs and forgeable audit identity:** `build.gradle` has no Security dependency and controllers have no authorization layer. `CustomerService.java:35` maps full names, birth dates, email and phone to list responses. Alert/case mutation accepts arbitrary `actor` strings. UI masking or an actor text field cannot meet backend authorization requirements.
3. **CTR/structuring currency mismatch:** `V5__detection_engine.sql` seeds 10,000/9,000–9,999 in default INR, whereas the requirement specifies USD equivalent. With the synthetic USD→INR 83.25 rate, USD 10,000 corresponds to INR 832,500. Make threshold currency explicit and normalize thresholds consistently; do not simply relabel INR thresholds as dollar equivalents.
4. **Missing FX undermines comparisons:** `CurrencyNormalizer.java:39` stores null base amounts when a rate is absent. `CtrRule.java:47` falls back to the original currency amount while comparing to a base-currency threshold. Reject/quarantine such records or represent a pending-normalization state before detection. FX lookup also selects the latest row without restricting `effective_from` to the applicable time.
5. **Event delivery is not durable or commit-safe:** for single/stream ingest the event is emitted before the surrounding `@Transactional` method commits. In-memory events disappear on process exit, and batch persistence/event emission has a crash gap. A durable outbox plus consumer idempotency is needed for the requested no-lost-alerts architecture.
6. **Concurrency guarantees are incomplete:** streaming performs lookup then insert (`TransactionIngestionService.java:98`). The database uniqueness constraint prevents duplicate rows but concurrent duplicate requests can fail with conflict rather than return the existing result. No durable processed-event key or alert uniqueness enforcement exists. Alert/case entities also lack optimistic versioning for concurrent analyst updates.
7. **Clearing alerts loses required disposition information:** `AlertStatusUpdateRequest` cannot carry a reason and `AlertService.java:50` stores null details. Cases can close with null reason (`CaseService.java:93`). Preserve actor, reason and timestamp and validate terminal transitions server-side.
8. **Status filtering defect — fixed:** both endpoints initially returned HTTP 500 because repository query parameters were strings against enum entity attributes. The services/repositories now use typed enums with case-insensitive parsing, invalid values return HTTP 400, and filtered alerts retain risk ordering. Five integration regressions pass.
9. **Some malformed HTTP inputs can become HTTP 500:** `GlobalExceptionHandler` handles bean validation and domain failures but routes otherwise-unhandled exceptions to a generic 500. Explicit handling is absent for invalid JSON/enum deserialization, request parameter type mismatch and missing multipart/request parts; verify these paths and return consistent 400/413/415 responses as appropriate.
10. **Credentials have committed fallback values:** `application.properties` and Compose allow environment overrides but fall back to `postgres` credentials. This is development convenience, not compliance with the requirement that passwords not be hardcoded.

## Deliverables and verification gaps

| Deliverable/nonfunctional target | Status |
|---|---|
| Source in a Git repository | Source exists, but `git status` in the supplied backend directory reported “not a git repository.” |
| ERD/schema and migrations | Present: README relationship diagram and Flyway V2–V5 SQL. |
| Synthetic customers/accounts/transactions | Present: 2 customers, 2 accounts, 10 transactions. At least three correctly defined typologies are not demonstrated: apparent structuring samples use INR 9,500–9,700; no configured high-risk-country transaction or 90-day baseline exists. |
| README architecture/setup | Present, correctly acknowledges detection/dashboard/security as future work, but some comments describe intended behavior as if implemented. |
| OpenAPI/Swagger specification | Missing: no dependency, configuration or specification artifact. README endpoint table alone is not OpenAPI. |
| Detection unit tests | Missing. Existing tests cover CSV parsing, ingestion, API ingest routes and application startup. |
| Migration validation against PostgreSQL | Not established by tests: `src/test/resources/application-test.properties` disables Flyway and uses Hibernate-created H2 schema. |
| 10,000 transactions under 2 minutes, sub-second streaming detection | Unverified; no benchmark and no operational detection engine. |
| Concurrent streams without duplicate/lost alerts | Not implemented or tested end to end. |
| SLF4J and layered architecture | Present. |
| Complete ingestion → detection → alert → case disposition demo | Not possible from the current backend pipeline. |

During the coordinating task, an initial compilation defect in `RuleConfig` was corrected: its Jackson 2 imports were migrated to the Jackson 3 package used by this Spring Boot dependency set. That defect is **fixed**, not a remaining blocker. The coordinator subsequently reported all four existing test suites passing; those suites do not establish operational detection, production migrations or security coverage.

This audit did not execute the test suite or claim runtime validation. Test results from the coordinating task should be reported separately. A minimal frontend can use existing ingestion, queue and case APIs honestly, but completion of the core challenge requires the missing backend pipeline, rules, security and audit behavior above.

Final coordinated verification: `./gradlew test` passes all **25 tests** (the original 20 plus five status-filter regressions). This uses H2 with Flyway disabled and does not establish PostgreSQL migration, detection, performance, or infrastructure correctness.

</details>
