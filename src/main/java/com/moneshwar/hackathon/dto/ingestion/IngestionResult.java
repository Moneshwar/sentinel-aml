package com.moneshwar.hackathon.dto.ingestion;

import com.moneshwar.hackathon.entity.enums.IngestionEntityType;
import com.moneshwar.hackathon.entity.enums.IngestionErrorType;

import java.util.List;

public record IngestionResult(
        String batchId,
        IngestionEntityType entityType,
        String sourceName,
        int totalRecords,
        int succeeded,
        int failed,
        List<IngestionErrorView> errors
) {
    public static IngestionResult empty(IngestionEntityType entityType, String sourceName, String batchId) {
        return new IngestionResult(batchId, entityType, sourceName, 0, 0, 0, List.of());
    }
}
