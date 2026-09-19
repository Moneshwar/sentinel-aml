package com.moneshwar.hackathon.exception;

import com.moneshwar.hackathon.entity.enums.IngestionErrorType;

/**
 * Raised while validating or persisting a single ingested record. Carries the
 * classification used when the failure is written to the ingestion error log.
 */
public class IngestionRecordException extends RuntimeException {

    private final IngestionErrorType errorType;
    private final String sourceReference;
    private final String rawRecord;

    public IngestionRecordException(IngestionErrorType errorType, String sourceReference, String rawRecord, String message) {
        super(message);
        this.errorType = errorType;
        this.sourceReference = sourceReference;
        this.rawRecord = rawRecord;
    }

    public IngestionErrorType getErrorType() {
        return errorType;
    }

    public String getSourceReference() {
        return sourceReference;
    }

    public String getRawRecord() {
        return rawRecord;
    }
}
