package com.moneshwar.hackathon.dto.ingestion;

import com.moneshwar.hackathon.entity.enums.IngestionEntityType;
import com.moneshwar.hackathon.entity.enums.IngestionErrorType;

import java.time.Instant;

public record IngestionErrorResponse(
        Long id,
        String batchId,
        IngestionEntityType entityType,
        String sourceName,
        String sourceReference,
        String rawRecord,
        IngestionErrorType errorType,
        String errorMessage,
        Instant occurredAt
) {
}
