# Sentinel AML

## Panel presentation guide

**Suggested duration:** 10–12 minutes, plus questions.\
**Focus:** Six detection rules, explainable alerts, and the investigation workflow.\
**Format:** Each numbered section is one slide; quoted speaker notes are for rehearsal. Appendices are backup material.

All monetary examples use USD unless stated otherwise. Thresholds and scores describe the application's default configuration. The examples and reference lists are synthetic demonstration data; these defaults are application controls, not a statement of universal reporting law.

---

## Slide 1 — Sentinel AML

**Transaction monitoring with explainable detection rules**

Detect suspicious patterns, prioritize alerts, and support analyst investigations.

**Java 21 · Spring Boot · PostgreSQL · React**

> **Speaker notes — 20 seconds**\
> “Sentinel is our anti-money-laundering transaction-monitoring application. It evaluates incoming transactions using six detection rules. When a pattern triggers, the analyst gets a risk score, a clear explanation, and the transactions supporting that finding.”

---

## Slide 2 — The monitoring problem

- Suspicious activity can appear as one large payment or several smaller payments.
- An account can receive funds and quickly transfer most of them out.
- A customer's activity can change sharply from their previous behaviour.
- Analysts need the evidence behind an alert and a way to record their decision.

**Our objective: turn transaction activity into a reviewable investigation.**

> **Speaker notes — 30 seconds**\
> “Looking at payments individually misses patterns spread across time. Sentinel combines individual checks with account history and customer behaviour. A detection is a reason to investigate; it does not establish that money laundering occurred.”

---

## Slide 3 — Transaction processing flow

1. **Ingest:** CSV, JSON batch, or an individual transaction over HTTP.
2. **Validate:** Check required fields and customer/account relationships.
3. **Normalize:** Convert amounts using exchange rates effective at the transaction time.
4. **Evaluate:** Run detection rules against relevant history and configured lists.
5. **Persist:** Save the transaction, alert findings, and associated audit entries together.
6. **Review:** Show the alert and its evidence in the analyst workspace.

**Individual requests wait for detection. Bulk imports return 202 and expose a job to poll.**

> **Speaker notes — 40 seconds**\
> “Bulk input and job metadata are stored in PostgreSQL before the API returns 202. A background worker processes each record, with detection and the transaction committing together. If detection fails, that record rolls back. Individual transaction requests wait for the result. Here, streaming means repeated individual HTTP submissions.”

---

## Slide 4 — Six complementary detection rules

| Detection rule | Main question | Scope | Default weight |
| --- | --- | --- | ---: |
| CTR | Is this transaction above the amount threshold? | One transaction | 20 |
| Structuring | Are amounts repeatedly just below the threshold? | One account, 24 hours | 30 |
| Rapid movement | Does money leave soon after arriving? | One account, 48 hours | 25 |
| High-risk screening | Does a country or counterparty match a configured list? | One transaction | 40 |
| Behavioural deviation | Is today's activity unusually high for this customer? | All customer accounts | 20 |
| Round numbers | Are exact round amounts repeatedly used? | One account, 24 hours | 10 |

> **Speaker notes — 30 seconds**\
> “Each rule covers a different pattern and returns an explanation with supporting transaction references. Multiple rules can contribute to the same customer alert. The weights determine review priority.”

---

## Slide 5 — CTR: large individual transactions

**Default trigger:** A transaction amount is **at least $10,000 equivalent**.

| Transaction amount | CTR result |
| --- | --- |
| $9,999 | Does not trigger |
| $10,000 | Triggers |
| $12,500 | Triggers |

- Default score contribution: **20**.
- Mandatory in the application; it cannot be disabled through the admin API.
- Configuration can lower the effective threshold, but cannot raise it above $10,000 equivalent.

> **Speaker notes — 35 seconds**\
> “CTR catches a large individual payment without requiring previous activity. Although the code calls it Cash Transaction Threshold, this implementation evaluates every transaction type, including transfers and payments. Amounts in other currencies are compared using their normalized equivalents.”

---

## Slide 6 — Structuring: repeated amounts below the threshold

**Default trigger:** At least **3 transactions**, each between **$9,000 and $9,999 inclusive**, in the **same account within 24 hours**.

| Time | Transaction | Qualifying count |
| --- | ---: | ---: |
| 09:00 | $9,100 | 1 |
| 09:20 | $9,500 | 2 |
| 09:40 | $9,800 | 3 — triggers |

**Default score contribution: 30.**

> **Speaker notes — 40 seconds**\
> “Every payment here is below the individual $10,000 threshold, but the repeated pattern warrants review. The rule counts transactions in the configured band; it does not simply add their values and compare the total with $10,000. It currently checks one account at a time and does not require a particular payment direction.”

---

## Slide 7 — Rapid movement: funds quickly leaving an account

**Default trigger:** Subsequent outflows reach **at least 80% of an incoming transaction**, within the same account's **48-hour lookback window**.

| Time | Activity | Amount |
| --- | --- | ---: |
| 10:00 | Incoming funds | $5,000 |
| 10:20 | Outgoing transfer | $2,000 |
| 10:40 | Outgoing transfer | $2,200 |

**Outgoing total / incoming amount = $4,200 / $5,000 = 84% → triggers.**

**Default score contribution: 25.**

> **Speaker notes — 40 seconds**\
> “The rule runs on an outgoing transaction and checks incoming funds in the same account's window. Multiple subsequent outgoing payments can meet the percentage. Earlier outflows are excluded. This detects a temporal pattern; it does not trace the ownership of specific funds.”

---

## Slide 8 — High-risk jurisdiction and counterparty screening

**A match in any one of these fields triggers review:**

- Transaction jurisdiction against the active high-risk country list.
- Counterparty country against the active high-risk country list.
- Counterparty account or name against enabled sanctions identifiers.

**Example:** A $100 transfer to an account on the configured sanctions list triggers.

**Any amount · Mandatory application control · Default score contribution: 40**

> **Speaker notes — 35 seconds**\
> “Amount does not determine whether this rule triggers. The implementation trims spaces and ignores case, then requires an exact match. It does not perform fuzzy name matching or automatically fetch external sanctions feeds. The lists are maintained through administration.”

---

## Slide 9 — Behavioural deviation: activity above the customer's baseline

**Default trigger:** Today's transaction **count or total value** exceeds **3 times** the daily average over the **previous 90 complete UTC days**.

| Measure | Example |
| --- | ---: |
| Previous 90-day transaction value | $90,000 |
| Historical daily average | $1,000 |
| Trigger boundary | More than $3,000 today |
| Today's value | $3,500 — triggers |

**All customer accounts · Default score contribution: 20**

> **Speaker notes — 45 seconds**\
> “This is a deterministic comparison with the customer's history. We calculate both transaction count and total value, and either can trigger. Today is excluded from the baseline, inactive days are included, and a customer with no historical transactions does not trigger this rule. Sparse history can make the baseline small, so tuning matters.”

---

## Slide 10 — Repeated round-number transactions

**Default trigger:** At least **3 positive exact multiples of $1,000 equivalent**, in the **same account within 24 hours**.

**Example: $2,000 + $5,000 + $3,000 → 3 qualifying payments → triggers.**

- Qualifying amounts can be different.
- One round-number transaction is insufficient.
- Currency comparisons use rates effective at each transaction's time.

**Default score contribution: 10.**

> **Speaker notes — 30 seconds**\
> “Repeated neatly sized payments provide another signal for review. This rule has the smallest default weight because round amounts can also occur in ordinary activity. It becomes more useful when combined with other findings.”

---

## Slide 11 — Combining findings into a risk score

**Alert score = sum of distinct triggered-rule weights, capped at 100.**

| Score | Severity |
| --- | --- |
| 0–29 | LOW |
| 30–59 | MEDIUM |
| 60–79 | HIGH |
| 80–100 | CRITICAL |

**Example:** A $20,000 deposit followed 30 minutes later by a $17,000 outbound transfer involving a configured high-risk country.

**CTR 20 + rapid movement 25 + high-risk screening 40 = 85 / CRITICAL.**

> **Speaker notes — 40 seconds**\
> “Findings are grouped by customer and UTC day. Each rule contributes once, even when several transactions trigger it. Supporting evidence is merged into the open alert, and its score does not decrease as findings accumulate. A score of 85 is a review priority, not an 85 percent probability of money laundering.”

---

## Slide 12 — Analyst review and investigation

1. Open the queue, ordered by risk score.
2. Read the triggered rules and explanations.
3. Inspect supporting transactions and customer/account history.
4. Create an investigation case and link the relevant alerts.
5. Record the decision and required disposition reason.
6. Review the audit history showing who acted and when.

**Access roles:** Administrator, analyst, and viewer; viewer lists mask sensitive information.

> **Speaker notes — 35 seconds**\
> “The analyst remains responsible for the investigation outcome. The server requires reasons for terminal decisions and derives the audit actor from authentication. Cleared or closed alerts are not automatically reopened by detection; new evidence may create a new review alert. SAR_FILED is a workflow status and does not submit a report externally.”

---

## Slide 13 — Live demo: explain a CRITICAL alert

**Scenario:** `DEMO-FIXTURE-C-CRITICAL_LAYERING`

| Activity | Amount | Timing |
| --- | ---: | --- |
| Incoming deposit | $30,000 | Start |
| Three outbound transfers involving the configured IR entry | $9,000 each | Within 30 minutes |

**Expected findings:**

| Rule | Why it triggers | Weight |
| --- | --- | ---: |
| CTR | $30,000 deposit exceeds the threshold | 20 |
| Structuring | Three $9,000 transfers | 30 |
| Rapid movement | $27,000 leaves after $30,000 arrives: 90% | 25 |
| High-risk screening | Outbound country matches the configured list | 40 |
| Round numbers | At least three amounts are multiples of $1,000 | 10 |

**Total: 125 → capped at 100 / CRITICAL.**

> **Speaker notes — 2 minutes, including the application demo**\
> “I will open this critical alert and connect each explanation to its supporting transactions. Notice that one investigation combines several patterns. I will then create a case and show where an analyst records the outcome and supporting reason.”
>
> In the application: open **Alert queue**, locate this scenario through its customer details/evidence, inspect the five rule explanations and four supporting transactions, then create a case and open **Investigations**. The IR country code is a match against our synthetic configured list. Exact results assume the default rules and reference data.

---

## Slide 14 — What Sentinel delivers

- **Six configurable rules** covering individual payments and historical patterns.
- **Explainable alerts** with transaction evidence and combined review priority.
- **An investigation workflow** with recorded decisions and audit history.
- **Consistent ingestion and detection** with transaction rollback on failure.

**Next improvements:** richer screening, calibrated behavioural baselines, and coordinated workers for larger workloads.

> **Speaker notes — 25 seconds**\
> “Sentinel demonstrates the full path from an incoming transaction to an explainable alert and an analyst investigation. The next step is to evaluate the rules against representative labelled data, improve screening coverage, and extend processing capacity. Thank you; I am ready for your questions.”

---

## Appendix A — Demo preparation

Use the fictional CSV dataset in [docs/demo-data](docs/demo-data/README.md).

1. Start the backend and frontend using the [README setup instructions](README.md#run-locally).
2. Sign in as an administrator with the configured credentials.
3. Confirm default rules, the synthetic USD→INR rate of **83.25** effective at the fixture dates, and an active IR country entry.
4. Import **customers.csv**, then **accounts.csv**, then **transactions.csv** from `docs/demo-data/`. Wait for each job to finish and inspect its failures before starting the dependent import.
5. On a fresh database, expect **13 customers, 13 accounts, and 129 transactions**.
6. Locate the `CRITICAL_LAYERING` example before presenting; prepare the alert detail and case workflow.

The fixture uses **15 January 2030** and a preceding behavioural baseline. Those fixed dates make the examples reproducible. Existing transaction references are rejected on CSV reimport. The fixture includes three HIGH and three CRITICAL scenarios under default settings; other alerts can also appear.

**Fallback if the application is unavailable:** Present the Slide 13 transaction table and explain the five rule contributions. Show it as an expected scenario, not a live result.

**Configuration demonstration:** Saved settings apply to subsequent evaluations. The explicit **Regenerate alerts** operation rebuilds historical findings and replaces existing alerts, including reviewed alerts and their case links. Use it only in a prepared disposable demo dataset if you choose to demonstrate it.

---

## Appendix B — Likely panel questions

**Is this an AI or machine-learning system?**\
The implemented engine uses deterministic rules. Behavioural deviation compares current activity with historical averages; no trained machine-learning model is involved.

**Why use six rules?**\
They cover different observable patterns. A threshold check detects a large payment; structuring detects repeated smaller payments; rapid movement checks timing; screening checks configured entities; behaviour checks customer history; round numbers add a repetition signal.

**How do you control false positives?**\
Administrators can tune supported thresholds, windows, and weights. Analysts inspect evidence and record dispositions. We have not measured precision or recall on a labelled production dataset, and dispositions do not automatically retrain or tune the engine.

**Can a rule be disabled?**\
The four optional rules can be disabled. CTR and high-risk screening remain mandatory application controls. Configuration is persisted and applied without restarting the application.

**How do multiple currencies work?**\
Amounts are normalized to a base currency, INR by default. Rules can specify a separate threshold currency, USD by default. Conversion uses effective historical rates; missing required rates reject processing.

**How do you avoid duplicate alerts?**\
Identical submissions through the idempotent stream endpoint return the existing transaction. Open findings merge by customer and UTC day, with distinct rule codes and supporting evidence. A reviewed finding with no new evidence is suppressed.

**What happens when transactions arrive out of order?**\
The engine evaluates the incoming transaction and re-evaluates affected later stored transactions within a bounded horizon derived from the configured windows. Each evaluation excludes activity after its own event time.

**Can structuring span several accounts?**\
The implemented structuring rule checks one account at a time. Behavioural deviation spans all accounts belonging to a customer. Network detection across multiple customers is future work.

**Does Sentinel block transfers or file regulatory reports?**\
The implemented workflow generates monitoring alerts and supports investigation decisions. It has no payment-blocking integration or external regulatory filing integration.

**How can another rule be added?**\
Implement `DetectionRule` as a Spring component, provide a stable code and configuration defaults, and return a finding with score, explanation, and evidence references. The engine handles alert persistence. Add rule-specific boundary and integration coverage.

---

## Appendix C — Engineering evidence and limits

**Recorded local benchmark — 19 September 2026**

| Measurement | Recorded result |
| --- | --- |
| Transactions ingested and evaluated | 10,000 |
| Customers / accounts | 100 / 100 |
| Input | 100 JSON batches of 100 records |
| Detection configuration | All six rules enabled; 10% jurisdiction hits |
| Bulk processing time | 53.178 seconds |
| Individual HTTP transaction median | 73.319 ms across 10 samples |
| Maximum across those samples | 80.967 ms |

Source: [recorded benchmark](docs/benchmark-result.json). These measurements describe the earlier synchronous bulk implementation; they do not benchmark the current asynchronous imports or establish production capacity or tail-latency guarantees.

**Verification evidence available in the repository**

- Rule conditions, threshold boundaries, and historical FX tests.
- Ingestion-to-alert integration, replay, and evidence aggregation tests.
- Security, privacy, disposition, and audit workflow checks.
- A fixture integration test asserting combined HIGH/CRITICAL scores and no alert for the ordinary-purchase scenario.

See [verification report](docs/verification.md) and [fixture integration test](src/test/java/com/moneshwar/hackathon/detection/DemoFixtureIntegrationTest.java). The verification report records prior test runs; it also states that browser interaction and rendered appearance were not visually verified in that run.

**Current limits**

- Asynchronous bulk imports use one backend instance and one worker; individual transaction requests remain synchronous. No Kafka or Redis is used.
- Exact counterparty matching; no fuzzy screening or automatic external feed updates.
- Sparse historical activity can make behavioural thresholds sensitive.
- Large backfills and concentrated customer histories can increase processing time.
- No production-labelled accuracy study or automatic rule calibration.

---

## Appendix D — Implementation references

| Topic | Repository reference |
| --- | --- |
| System design | [Architecture guide](docs/architecture.md) |
| Rule orchestration and alert scoring | [DetectionEngine.java](src/main/java/com/moneshwar/hackathon/detection/DetectionEngine.java) |
| All six rule implementations | [Detection rules](src/main/java/com/moneshwar/hackathon/detection/rules/) |
| Default settings and configuration validation | [RuleSettingsService.java](src/main/java/com/moneshwar/hackathon/service/config/RuleSettingsService.java) |
| Demo scenarios and assumptions | [Demo data guide](docs/demo-data/README.md) |
| API contract | [OpenAPI specification](docs/openapi.yaml) |
