# Getting started

[← Project overview](../README.md) · [All documentation](README.md)

Run commands from the repository root unless a step says otherwise.

## Run locally

Requires Java 21, Docker Compose or PostgreSQL 14+, and Node.js 20.19+ (20.x) or 22.12+ with npm for the frontend.

1. Run `cp .env.example .env` and supply your own database and API-user passwords.
   `POSTGRES_PASSWORD` and `SPRING_DATASOURCE_PASSWORD` must match. Set the database port/URL
   to an unused port if another PostgreSQL instance is already running.
2. Load the environment and start the backend:

```sh
set -a
source .env
set +a
docker compose up -d --wait
./gradlew bootRun
```

Flyway applies the schema migrations on startup. The API defaults to `http://localhost:8080`.
The API has no fallback passwords: roles with an empty password are unavailable.

3. In another terminal start the frontend:

```sh
cd frontend
npm ci
npm run dev
```

Open the local URL printed by Vite (normally `http://localhost:5173`).
Sign in as `admin`, `analyst` or `viewer` with the password configured for that role.
Vite proxies `/api` to port 8080; set `VITE_API_PROXY_TARGET` for another backend origin.
Production static hosting requires an `/api` reverse proxy and HTTPS for Basic authentication.
Credentials stay in frontend memory, and signing out or refreshing discards them.

| Role | Access |
| --- | --- |
| ADMIN | Ingest/import data, administer rules/FX/risk lists, investigate and dispose alerts/cases |
| ANALYST | Read authorized customer/transaction details, investigate and dispose alerts/cases |
| VIEWER | Read masked queues and lists; no sensitive customer/transaction details or mutations |

## Try the demo data

Use the CSV files in [docs/demo-data](demo-data/README.md): **13 fictional customers,
13 accounts, and 129 transactions**. The dataset uses 15 January 2030 and a preceding
90-day behavioural baseline; seeded FX and screening entries are synthetic.

1. Sign in as `admin` and open **Import data**.
2. Import `customers.csv`; wait for the job to complete and check failures.
3. Import `accounts.csv`, wait for completion, then import `transactions.csv` and wait again.
4. With default rules/reference data, inspect three HIGH and three CRITICAL scenarios.
   `CRITICAL_LAYERING` combines five rules into a score of **100 / CRITICAL**.
5. Open the evidence, create an investigation case, and record a disposition reason.

Existing transaction references are rejected on CSV reimport. A `COMPLETED` job can still
contain rejected rows; inspect its failure count. See the [panel presentation](../presentation.md)
for rule examples, speaker notes, and the demo script.

## Troubleshooting and deployment

- Run **one backend instance** against the import queue. Queued jobs survive restarts;
  interrupted running jobs become FAILED and need review before resubmission.
- If sign-in fails, verify the corresponding `SENTINEL_*_PASSWORD` is configured in the
  backend process. A blank password disables that role.
- If startup cannot connect to PostgreSQL, check database health and matching database
  name, username, password, port, and JDBC URL in `.env`.
- If a record fails with missing FX or an unknown account, configure an effective exchange
  rate or complete the prerequisite customer/account import before resubmitting.
- If a job stays QUEUED, check worker configuration and backend logs. Polling and recovery
  details are in [async ingestion](async-ingestion.md).

## Tests and demo scripts

```sh
./gradlew test
python3 scripts/demo.py
python3 scripts/benchmark.py
cd frontend
npm run build
npm run lint
```

The demo reads the configured admin/analyst passwords from the environment and uses a unique
synthetic run prefix. It creates all six rule scenarios, checks evidence/scores and replay,
opens/disposes a case, clears an alert with a reason, and verifies authenticated audit identity.
It creates persistent synthetic records in the selected development database; it never deletes
existing data. Set `SENTINEL_BASE_URL` to use another backend origin.

CSV examples covering the typologies are in [docs/demo-data](demo-data/README.md).
The benchmark creates 10,000 simulated payments across 100 customers with all rules enabled,
checks high-risk alerts, measures bulk time and samples individual streaming latency.

See [verification results](verification.md) for measured results and limits.
The earlier [requirements audit](requirements-audit.md) and
[architecture audit](architecture-audit.md) are historical baseline reviews;
Redis/Kafka remain excluded; asynchronous imports are now implemented as described in [async ingestion](async-ingestion.md).
