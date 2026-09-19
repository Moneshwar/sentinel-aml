# Synthetic demonstration data

These files contain **13 fictional customers, 13 accounts, and 129 transactions**, including **three HIGH and three CRITICAL alert scenarios**, lower-risk examples, and ordinary purchases that should not alert. No real customer or authentication data is included. Import `customers.csv`, then `accounts.csv`, then `transactions.csv` through the administrator UI. Wait for each asynchronous job to complete and inspect its failure count before submitting the next file. They use fixed identifiers beginning `DEMO-FIXTURE` and an event date of **2030-01-15**. The behavioral baseline covers the preceding 90 UTC days. Fixed future timestamps make the fixture reproducible and allow the shipped effective reference data to apply.

| Account suffix | Activity | Expected triggered rule |
| --- | --- | --- |
| STRUCTURING | Three USD 9,500 transfers within minutes | STRUCTURING |
| RAPID | USD 10,000 deposit, then USD 8,500 outbound after 30 minutes | CTR and RAPID_MOVEMENT |
| HIGH_RISK | USD 1 transfer involving IR | HIGH_RISK_JURISDICTION |
| ROUND | Three USD 1,000 transfers | ROUND_NUMBER |
| BEHAVIOR | USD 100 on each preceding day, then USD 1,000 surge | BEHAVIORAL_DEVIATION |

## Combined risk scenarios

The original `HIGH_RISK` country example triggers only one rule worth 40 points, so its alert severity is **MEDIUM**. HIGH requires 60–79 points; CRITICAL requires 80–100. The added scenarios combine findings for the same customer/day to demonstrate these queue priorities.

| Account suffix | Added transactions | Expected combined rules | Score / severity |
| --- | --- | --- | --- |
| HIGH_VALUE_RISK | USD 12,500 outbound to IR | CTR + HIGH_RISK_JURISDICTION | **60 / HIGH** |
| HIGH_STRUCTURING | USD 9,200, 9,600 and 9,800 outbound to IR over 40 minutes | STRUCTURING + HIGH_RISK_JURISDICTION | **70 / HIGH** |
| HIGH_DOMESTIC | USD 30,000 deposit, then domestic outflows of 9,500, 9,600 and 9,700 within 45 minutes | CTR + STRUCTURING + RAPID_MOVEMENT | **75 / HIGH** |
| CRITICAL_RAPID | USD 20,000 deposit, then USD 17,000 outbound to IR after 30 minutes | CTR + RAPID_MOVEMENT + HIGH_RISK_JURISDICTION | **85 / CRITICAL** |
| CRITICAL_LAYERING | USD 30,000 deposit, then three USD 9,000 outflows to IR within 30 minutes | CTR + STRUCTURING + RAPID_MOVEMENT + ROUND_NUMBER + HIGH_RISK_JURISDICTION | **100 / CRITICAL**, capped from 125 |
| CRITICAL_FX | INR 1,665,000 deposit, then INR 1,415,250 outbound to IR after 45 minutes | CTR + RAPID_MOVEMENT + HIGH_RISK_JURISDICTION | **85 / CRITICAL** |
| LOW_CTR | USD 12,500 domestic outbound payment | CTR | **20 / LOW** |
| NORMAL | 12 small, non-round domestic purchases on the event day | None | **No alert** |

Each new scenario uses its own customer/account so its evidence and score are easy to explain. Master-data risk ratings remain LOW: alert severity comes from detected transaction patterns, not a preassigned customer label. These CSVs create alerts; an analyst opens investigation cases from the alert detail screen.

The default enabled rules, thresholds and weights, the synthetic USD→INR rate of **83.25** at the transaction dates, and an active IR jurisdiction entry are required for the exact results above. The FX example uses seeded demonstration rates, not current market prices. Prior baseline days can also produce behavioral alerts because the engine includes zero-activity days when calculating early averages. Alerts combine triggered rules/evidence for a customer and UTC day; the number of alerts therefore differs from the number of rule matches.

If the original 100 transactions are already imported, import the updated customer and account files first, then the transaction file. Existing customer/account records are upserted. On the first import of this expansion, the original 100 transaction references will report duplicates and the **29 new transactions** will be accepted independently. Refresh the alert queue afterward; the new HIGH and CRITICAL alerts remain open for review. A fresh database should accept all 129 transactions with no rejections.

## Regeneration and verification

Regenerate these exact files without contacting a server:

```sh
python3 scripts/demo.py --write-fixtures docs/demo-data
```

To run the API demonstration against a development database, configure the backend with `SENTINEL_ADMIN_PASSWORD` and `SENTINEL_ANALYST_PASSWORD`, then export those same values in the shell. The backend usernames are `admin` and `analyst`. No password values are written to files or printed by the script.

```sh
export SENTINEL_BASE_URL=http://localhost:8080
python3 scripts/demo.py
```

The script uses only Python's standard library. It checks credentials/configuration, creates a unique synthetic run, submits transactions, verifies all six rules, combined scores/severities and evidence, checks that ordinary purchases produce no alert, confirms an idempotent stream replay, creates and closes an investigation, clears its alert with a reason, and checks that audit actions are attributed to authenticated `analyst` despite a spoofed request actor. It verifies that reasons are required and closed cases cannot reopen. The HIGH and CRITICAL examples remain open.

The CSV files are also exercised through the backend's real import and detection services by an isolated H2 integration test:

```sh
./gradlew test --tests '*DemoFixtureIntegrationTest'
```

By default API demo timestamps use tomorrow at noon UTC, ensuring already-effective seeded reference data applies consistently. Override with `--date YYYY-MM-DD`. Each run gets a new prefix; `--prefix NAME` is available for reproducibility, but reuse of an existing prefix will conflict with non-stream transaction ingestion. The script does not change rule/reference configuration or delete its records. Synthetic records remain available for UI review.

Use a development database. The script performs real API writes and deliberately disposes one synthetic alert and case. CSV fixture reimports may return duplicate transaction errors; the stream endpoint is the idempotent replay path.
