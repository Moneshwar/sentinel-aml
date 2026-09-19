CREATE TABLE ingestion_jobs (
    id VARCHAR(64) PRIMARY KEY,
    entity_type VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL,
    format VARCHAR(10) NOT NULL,
    source_name VARCHAR(255),
    submitted_by VARCHAR(255) NOT NULL,
    total_records INTEGER,
    processed INTEGER NOT NULL DEFAULT 0,
    succeeded INTEGER NOT NULL DEFAULT 0,
    failed INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    failure_message VARCHAR(1000)
);
CREATE INDEX idx_ingestion_jobs_queue ON ingestion_jobs (status, created_at, id);
CREATE INDEX idx_ingestion_jobs_recent ON ingestion_jobs (created_at DESC, id);
CREATE TABLE ingestion_job_payloads (
    job_id VARCHAR(64) PRIMARY KEY REFERENCES ingestion_jobs(id) ON DELETE CASCADE,
    content TEXT NOT NULL
);
