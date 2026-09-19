package com.moneshwar.hackathon.dto.ingestion;

import com.moneshwar.hackathon.entity.IngestionJob;
import com.moneshwar.hackathon.entity.enums.*;
import java.time.Instant;

public record IngestionJobResponse(String jobId, String batchId, IngestionEntityType entityType,
        String sourceName, IngestionJobStatus status, Integer totalRecords, int processed,
        int succeeded, int failed, Instant createdAt, Instant startedAt, Instant finishedAt,
        String failureMessage, String statusUrl, IngestionResult result) {
    public static IngestionJobResponse from(IngestionJob j, IngestionResult result) {
        return new IngestionJobResponse(j.getId(), j.getId(), j.getEntityType(), j.getSourceName(),
                j.getStatus(), j.getTotalRecords(), j.getProcessed(), j.getSucceeded(), j.getFailed(),
                j.getCreatedAt(), j.getStartedAt(), j.getFinishedAt(), j.getFailureMessage(),
                "/api/v1/ingestion/jobs/" + j.getId(), result);
    }
}
