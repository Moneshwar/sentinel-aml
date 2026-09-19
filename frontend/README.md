# Sentinel AML frontend

Minimal React + TypeScript analyst workspace using the real `/api/v1` APIs.

```sh
cd frontend
npm ci
npm run dev
```

Start Spring Boot on port 8080 first (see repository README). Vite proxies `/api` to `http://localhost:8080`. Set `VITE_API_PROXY_TARGET=http://localhost:18080` for another backend port. Open the URL printed by Vite. A production build requires a same-origin `/api` reverse proxy; the Vite development proxy does not apply to generated files.

```sh
npm run build
npm run lint
```

## Authentication and roles

Sign in using a backend-configured username/password. `/api/v1/session` verifies HTTP Basic credentials and returns the authenticated username and roles. Credentials remain in memory, are sent with API requests, and are cleared on logout or HTTP 401. Refreshing the page requires signing in again. Deploy over HTTPS.

- **ADMIN:** investigation actions, CSV/transaction ingestion, rule configuration, exchange rates, high-risk jurisdictions and counterparty sanctions.
- **ANALYST:** investigation actions and customer/transaction details.
- **VIEWER:** masked list views without mutations or sensitive detail actions.

The backend enforces permissions and derives the audit actor from authenticated identity. Customer/account identifiers are masked in the UI. Raw import source rows are not rendered. Customer profile fields are shown only in authorized ADMIN/ANALYST detail views after fetching the protected numeric-ID endpoint.

## Included

- Risk-sorted paginated alert queue, status filters, triggered rules, explanation and transaction evidence; status, assignment, disposition and reason updates; investigation case creation.
- Case list/detail with status, assignment, disposition and reason. Closed records are read only. A reason is required for alert clearing/closure and case closure/SAR-filed status.
- Read-only, paginated audit history on alert/case details, showing timestamp, authenticated actor, action, transition and reason; refreshed after successful changes.
- Masked customer list, authorized customer profile, paginated accounts and account transaction timelines. Missing normalized amounts display “Not normalized”.
- CSV imports in customer → account → transaction order using backend headers. Background processing with queued/running/completed/failed states, persisted progress, recent jobs, and up to 200 returned error details with the full rejected count shown.
- Single transaction submission through the idempotent `/transactions/stream` endpoint.
- Administrator configuration editors using server-provided rule JSON and reference data. Required CTR/high-risk rules cannot be disabled. Effective dates use ISO timestamps.
- Administrator alert regeneration evaluates stored transactions with saved rules after typed confirmation. It replaces all alerts/statuses/assignments/dispositions, removes case-alert links, and retains cases, transactions and audit history. Save or discard rule drafts first; rule editing is locked while the request runs.
- Severity distribution explicitly scoped to the currently loaded filtered alert page, not a global aggregate.

All records and import progress come from the backend. CSV upload returns a queued job; the import screen polls status and shows the latest 10 jobs when you return. CSV totals are unknown until processing ends, so progress uses record counts rather than a percentage. Poll failures preserve the last known progress and retry automatically. Refresh the alert queue to retrieve updated alerts. Detection executes for each transaction in the backend worker. Individual transaction entry still waits for processing.

`SAR_FILED` records an investigation status; this frontend does not file reports with an external authority. Server errors may contain record-specific details and are displayed as returned. Rule/reference changes affect subsequent backend evaluations. Use Configuration → Detection rules → Regenerate alerts to apply saved controls to all stored transactions; the confirmation explains the replacement of existing alerts and removal of case links.
