package com.moneshwar.hackathon.service.ingestion;

import com.moneshwar.hackathon.dto.common.PageResponse;
import com.moneshwar.hackathon.dto.ingestion.*;
import com.moneshwar.hackathon.entity.*;
import com.moneshwar.hackathon.entity.enums.*;
import com.moneshwar.hackathon.exception.ResourceNotFoundException;
import com.moneshwar.hackathon.repository.*;
import com.moneshwar.hackathon.security.AuditActor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

@Service
public class IngestionJobService {
    private final IngestionJobRepository jobs;
    private final IngestionJobPayloadRepository payloads;
    private final IngestionErrorRepository errors;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final int maxPending;
    private final int maxBatchRecords;
    private final long maxInputBytes;

    public IngestionJobService(IngestionJobRepository jobs, IngestionJobPayloadRepository payloads,
            IngestionErrorRepository errors, ObjectMapper mapper, PlatformTransactionManager manager,
            @Value("${sentinel.ingestion.max-pending-jobs:20}") int maxPending,
            @Value("${sentinel.ingestion.max-batch-records:10000}") int maxBatchRecords,
            @Value("${sentinel.ingestion.max-input-bytes:52428800}") long maxInputBytes) {
        this.jobs = jobs; this.payloads = payloads; this.errors = errors; this.mapper = mapper;
        this.transactions = new TransactionTemplate(manager);
        this.maxPending = maxPending; this.maxBatchRecords = maxBatchRecords; this.maxInputBytes = maxInputBytes;
    }

    public IngestionJobResponse submitCsv(IngestionEntityType type, MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("Select a non-empty CSV file");
        checkSize(file.getSize());
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) name = "upload.csv";
        // Store the bytes durably before the multipart request is released.
        return enqueue(type, "CSV", name.substring(0, Math.min(name.length(), 255)), null,
                Base64.getEncoder().encodeToString(file.getBytes()));
    }

    public IngestionJobResponse submitBatch(IngestionEntityType type, List<?> records) {
        if (records.size() > maxBatchRecords)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Batch exceeds " + maxBatchRecords + " records");
        byte[] bytes = mapper.writeValueAsBytes(records);
        checkSize(bytes.length);
        return enqueue(type, "JSON", "BATCH", records.size(), Base64.getEncoder().encodeToString(bytes));
    }

    private void checkSize(long bytes) {
        if (bytes > maxInputBytes)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Import exceeds the configured input size limit");
    }

    // Admission is serialized for this application's single-instance worker deployment.
    private synchronized IngestionJobResponse enqueue(IngestionEntityType type, String format,
            String sourceName, Integer total, String content) {
        return transactions.execute(tx -> {
            if (jobs.countByStatusIn(List.of(IngestionJobStatus.QUEUED, IngestionJobStatus.RUNNING)) >= maxPending)
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Import queue is full; try again later");
            IngestionJob job = new IngestionJob();
            job.setId(UUID.randomUUID().toString()); job.setEntityType(type); job.setFormat(format);
            job.setSourceName(sourceName); job.setSubmittedBy(AuditActor.current());
            job.setStatus(IngestionJobStatus.QUEUED); job.setTotalRecords(total); job.setCreatedAt(Instant.now());
            jobs.save(job);
            IngestionJobPayload payload = new IngestionJobPayload();
            payload.setJobId(job.getId()); payload.setContent(content); payloads.save(payload);
            return IngestionJobResponse.from(job, null);
        });
    }

    public IngestionJobResponse get(String id) {
        IngestionJob job = jobs.findById(id).orElseThrow(() -> new ResourceNotFoundException("Unknown ingestion job: " + id));
        boolean terminal = job.getStatus() == IngestionJobStatus.COMPLETED || job.getStatus() == IngestionJobStatus.FAILED;
        IngestionResult result = terminal ? new IngestionResult(id, job.getEntityType(), job.getSourceName(),
                job.getProcessed(), job.getSucceeded(), job.getFailed(),
                errors.findByBatchId(id, PageRequest.of(0, 200, Sort.by("id"))).stream()
                        .map(e -> new IngestionErrorView(e.getSourceReference(), e.getErrorType(), e.getErrorMessage(), e.getRawRecord())).toList()) : null;
        return IngestionJobResponse.from(job, result);
    }

    public PageResponse<IngestionJobResponse> list(Pageable pageable) {
        return PageResponse.from(jobs.findAll(PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")))), j -> IngestionJobResponse.from(j, null));
    }

    public void finish(String id, IngestionResult result, String failure) {
        transactions.executeWithoutResult(tx -> {
            IngestionJob job = jobs.findById(id).orElseThrow();
            if (result != null) {
                job.setProcessed(result.totalRecords()); job.setTotalRecords(result.totalRecords());
                job.setSucceeded(result.succeeded()); job.setFailed(result.failed());
            }
            job.setStatus(failure == null ? IngestionJobStatus.COMPLETED : IngestionJobStatus.FAILED);
            job.setFailureMessage(failure); job.setFinishedAt(Instant.now()); jobs.save(job);
            payloads.deleteById(id);
        });
    }
}
