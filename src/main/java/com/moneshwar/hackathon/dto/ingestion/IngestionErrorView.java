package com.moneshwar.hackathon.dto.ingestion;

import com.moneshwar.hackathon.entity.enums.IngestionErrorType;

public record IngestionErrorView(
        String sourceReference,
        IngestionErrorType errorType,
        String message,
        String rawRecord
) {
}
