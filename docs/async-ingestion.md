# Asynchronous ingestion

CSV and JSON batch endpoints now return **202 Accepted** after persisting the input and a job in PostgreSQL. This is an API contract change: callers must poll the job before using its results or submitting dependent data. Individual customer/account/transaction creation and `/transactions/stream` remain synchronous. Detection and its transaction still commit atomically for every accepted transaction in the worker.

## API

All paths below are relative to `/api/v1` and require ADMIN authentication.

- `POST /ingestion/{customers,accounts,transactions}/csv`: multipart `file`.
- `POST /ingestion/{customers,accounts,transactions}/batch`: typed JSON array.
- `POST /transactions/batch`: alias for asynchronous transaction batches.
- `GET /ingestion/jobs/{jobId}`: current status, counts, timestamps, failure reason and final `result`.
- `GET /ingestion/jobs?page=0&size=10`: recent jobs, newest first. List entries omit the final result; fetch the detail endpoint for errors.
- `GET /ingestion/errors?batchId={jobId}`: all persisted row errors, paginated.

Submission returns a `Location: /api/v1/ingestion/jobs/{jobId}` header and a job response, including:

```json
{
  "jobId": "c0ffd7f1-6e94-44f2-88b4-97d0d08105b1",
  "batchId": "c0ffd7f1-6e94-44f2-88b4-97d0d08105b1",
  "status": "QUEUED",
  "totalRecords": null,
  "processed": 0,
  "succeeded": 0,
  "failed": 0,
  "statusUrl": "/api/v1/ingestion/jobs/c0ffd7f1-6e94-44f2-88b4-97d0d08105b1",
  "result": null
}
```

The response also carries entity/source information and timestamps. Poll at roughly 1–2 second intervals until status is `COMPLETED` or `FAILED`. `QUEUED` means waiting; `RUNNING` means the worker has claimed the job. `COMPLETED` can include rejected records: check `failed` and `result.errors`. `FAILED` means processing stopped unexpectedly, and some rows may already have committed. For a failed job, `result` describes the last saved partial progress.

`processed` counts handled input records, `succeeded` counts accepted records, and `failed` counts rejected records. JSON batches have a known `totalRecords` at submission; CSV totals are `null` until normal completion. Up to 200 error details appear in the result; the full error log remains available. Malformed CSV headers or unrecoverable parser errors use the existing behavior: record a rejection, preserve prior rows, and end parsing. This can yield a completed job with rejected records rather than a worker failure.

## Persistence and recovery

Migration `V8__async_ingestion_jobs.sql` adds `ingestion_jobs` and `ingestion_job_payloads`. The submission transaction stores both job metadata and Base64-encoded input before returning 202. Status polling reads metadata without loading the input. CSV bytes and JSON request lists are materialized in memory within configured limits; CSV parsing itself remains incremental. Payloads are deleted atomically with terminal job status. Job history and error records remain for review; no automatic retention purge is configured.

A single scheduled worker checks the queue every 500 ms by default, claims the oldest job, and processes it before taking another. Progress is persisted after each committed/rejected row. The authenticated submitter is recorded on the job and restored as the actor for detection audit records.

On restart, queued jobs retain their payload and are processed. Jobs left RUNNING are marked FAILED and are **not automatically replayed**. Previously committed rows remain. A crash between a record commit and its progress update can leave counters behind the database; review accepted records and errors before resubmission. This version does not provide automatic retries, resumable checkpoints, cancellation, or exactly-once submission. Existing unique transaction references prevent duplicate transactions on resubmission; duplicates are reported as rejected records. Customer/account imports retain their existing upsert behavior.

**Deploy one backend application instance against this queue.** Startup recovery and admission limits assume a single instance; they are not a distributed lease protocol. A multi-instance deployment needs coordinated ownership and database-wide queue admission first.

## Limits and operation

| Setting | Default | Purpose |
| --- | --- | --- |
| `sentinel.ingestion.max-pending-jobs` | 20 | Maximum queued + running jobs; excess submissions return 429 |
| `sentinel.ingestion.max-batch-records` | 10000 | Maximum JSON records; excess returns 413 |
| `sentinel.ingestion.max-input-bytes` | 52428800 | Maximum serialized input bytes; excess returns 413 |
| `spring.servlet.multipart.max-file-size` / `max-request-size` | 50MB | HTTP multipart upload limits |
| `sentinel.ingestion.poll-delay-ms` | 500 | Delay between worker queue checks |
| `sentinel.ingestion.worker-enabled` | true | Disable only for controlled maintenance/tests; submissions still queue |

Empty CSV uploads return 400; malformed JSON fails request binding before a job is created. Record-level validation happens in the worker. A 202 response acknowledges stored work, not completed detection. Upload/persistence time remains part of the request, but per-record processing no longer holds the HTTP connection open.

The frontend displays recent imports, polls running jobs, and retries transient polling failures. You can navigate away after upload acceptance and return to find the job. Wait for customers to complete successfully before accounts, then accounts before transactions. `scripts/demo.py` and `scripts/benchmark.py` poll completion explicitly; historical benchmark timings do not measure the async implementation.
