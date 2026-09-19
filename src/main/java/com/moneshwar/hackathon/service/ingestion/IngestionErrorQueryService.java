package com.moneshwar.hackathon.service.ingestion;

import com.moneshwar.hackathon.dto.common.PageResponse;
import com.moneshwar.hackathon.dto.ingestion.IngestionErrorResponse;
import com.moneshwar.hackathon.entity.IngestionError;
import com.moneshwar.hackathon.repository.IngestionErrorRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class IngestionErrorQueryService {

    private final IngestionErrorRepository ingestionErrorRepository;

    public IngestionErrorQueryService(IngestionErrorRepository ingestionErrorRepository) {
        this.ingestionErrorRepository = ingestionErrorRepository;
    }

    public PageResponse<IngestionErrorResponse> list(String batchId, Pageable pageable) {
        Page<IngestionError> page = (batchId != null && !batchId.isBlank())
                ? ingestionErrorRepository.findByBatchId(batchId, pageable)
                : ingestionErrorRepository.findAllByOrderByOccurredAtDesc(pageable);
        return PageResponse.from(page, this::toResponse);
    }

    private IngestionErrorResponse toResponse(IngestionError error) {
        return new IngestionErrorResponse(
                error.getId(),
                error.getBatchId(),
                error.getEntityType(),
                error.getSourceName(),
                error.getSourceReference(),
                error.getRawRecord(),
                error.getErrorType(),
                error.getErrorMessage(),
                error.getOccurredAt());
    }
}
