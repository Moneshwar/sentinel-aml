# API guide

[← Project overview](../README.md) · [All documentation](README.md)

All application routes are under `/api/v1`. See the [OpenAPI specification](openapi.yaml)
for request schemas, responses and authentication.

| Routes | Purpose |
| --- | --- |
| `/session` | Current authenticated username and roles |
| `/customers`, `/accounts`, `/transactions` | Create/read records, paginated lists and detail |
| `/customers/by-id/{id}` | Authorized customer detail by numeric record ID |
| `/transactions/stream` | Idempotent individual ingest (201 new, 200 replay, 409 conflict) |
| `/ingestion/{customers,accounts,transactions}/csv` | Multipart `file`; returns 202 + queued job |
| `/ingestion/{customers,accounts,transactions}/batch`, `/transactions/batch` | JSON arrays; returns 202 + queued job |
| `/ingestion/jobs`, `/ingestion/jobs/{id}` | ADMIN recent jobs and persisted progress/results |
| `/ingestion/errors` | Paginated rejected records, optional batch ID |
| `/alerts`, `/cases` | Risk queue and investigation workflow |
| `/alerts/{ref}/status`, `/cases/{ref}/status` | PATCH state/assignment/disposition |
| `/audit?entityRef=...` | Authenticated analyst audit history |
| `/admin/alerts/regenerate` | ADMIN GET preview counts; POST confirmed atomic rebuild from stored transactions |
| `/rules`, `/rules/{code}` | ADMIN read/update enabled flag and validated JSON configuration |
| `/settings/exchange-rates` | ADMIN effective-dated FX list/upsert |
| `/settings/high-risk-jurisdictions` | ADMIN effective-dated country list/upsert |
| `/settings/sanctioned-counterparties` | ADMIN exact identifier/name list/upsert |

Amounts accept up to 17 integer digits and 2 decimal places. Transaction timestamps are stored at
microsecond precision. Pagination supports `page`, `size`, and `sort` (maximum page size 500).
Validation/auth/conflict errors use consistent ProblemDetail JSON with appropriate HTTP status.

For background-import request and polling examples, see [asynchronous ingestion](async-ingestion.md).
