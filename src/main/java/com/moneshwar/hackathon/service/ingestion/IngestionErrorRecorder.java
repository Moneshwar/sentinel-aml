package com.moneshwar.hackathon.service.ingestion;

import com.moneshwar.hackathon.dto.ingestion.IngestionErrorView;
import com.moneshwar.hackathon.entity.IngestionError;
import com.moneshwar.hackathon.entity.enums.IngestionEntityType;
import com.moneshwar.hackathon.entity.enums.IngestionErrorType;
import com.moneshwar.hackathon.repository.IngestionErrorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class IngestionErrorRecorder {

    private static final Logger log = LoggerFactory.getLogger(IngestionErrorRecorder.class);
    private static final int MAX_RAW_RECORD = 4000;
    private static final int MAX_MESSAGE = 1000;

    private final IngestionErrorRepository ingestionErrorRepository;

    public IngestionErrorRecorder(IngestionErrorRepository ingestionErrorRepository) {
        this.ingestionErrorRepository = ingestionErrorRepository;
    }

    public IngestionErrorView record(String batchId,
                                     IngestionEntityType entityType,
                                     String sourceName,
                                     String sourceReference,
                                     String rawRecord,
                                     IngestionErrorType errorType,
                                     String message) {
        String safeMessage = truncate(message, MAX_MESSAGE);
        String safeRaw = truncate(rawRecord, MAX_RAW_RECORD);
        log.warn("Ingestion error [{}] batch={} entity={} ref={}: {}",
                errorType, batchId, entityType, sourceReference, safeMessage);
        ingestionErrorRepository.save(IngestionError.builder()
                .batchId(batchId)
                .entityType(entityType)
                .sourceName(sourceName)
                .sourceReference(sourceReference)
                .rawRecord(safeRaw)
                .errorType(errorType)
                .errorMessage(safeMessage)
                .build());
        return new IngestionErrorView(sourceReference, errorType, safeMessage, safeRaw);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
