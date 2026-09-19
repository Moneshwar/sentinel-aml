package com.moneshwar.hackathon.controller;

import com.moneshwar.hackathon.dto.common.PageResponse;
import com.moneshwar.hackathon.dto.ingestion.IngestionJobResponse;
import com.moneshwar.hackathon.entity.enums.IngestionEntityType;
import com.moneshwar.hackathon.service.ingestion.IngestionJobService;
import com.moneshwar.hackathon.dto.transaction.TransactionRequest;
import com.moneshwar.hackathon.dto.transaction.TransactionResponse;
import com.moneshwar.hackathon.entity.Transaction;
import com.moneshwar.hackathon.entity.enums.IngestionSource;
import com.moneshwar.hackathon.mapper.TransactionMapper;
import com.moneshwar.hackathon.service.TransactionQueryService;
import com.moneshwar.hackathon.service.ingestion.TransactionIngestionService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private final TransactionIngestionService ingestionService;
    private final TransactionQueryService queryService;
    private final TransactionMapper transactionMapper;
    private final IngestionJobService jobs;

    public TransactionController(TransactionIngestionService ingestionService,
                                 TransactionQueryService queryService,
                                 TransactionMapper transactionMapper, IngestionJobService jobs) {
        this.ingestionService = ingestionService;
        this.queryService = queryService;
        this.transactionMapper = transactionMapper;
        this.jobs = jobs;
    }

    @PostMapping
    public ResponseEntity<TransactionResponse> create(@Valid @RequestBody TransactionRequest request) {
        Transaction saved = ingestionService.ingest(request, IngestionSource.API);
        return ResponseEntity.status(HttpStatus.CREATED).body(transactionMapper.toResponse(saved));
    }

    @PostMapping("/batch")
    public ResponseEntity<IngestionJobResponse> batch(@RequestBody List<TransactionRequest> requests) {
        return IngestionController.accepted(jobs.submitBatch(IngestionEntityType.TRANSACTION, requests));
    }

    /**
     * Continuous, idempotent ingestion endpoint for transactions arriving one-by-one.
     * Returns 201 on first ingest, 200 when replaying an already-persisted transaction_ref.
     */
    @PostMapping("/stream")
    public ResponseEntity<TransactionResponse> stream(@Valid @RequestBody TransactionRequest request) {
        var outcome = ingestionService.ingestStreamingWithOutcome(request);
        HttpStatus status = outcome.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(transactionMapper.toResponse(outcome.transaction()));
    }

    @GetMapping
    public PageResponse<TransactionResponse> list(Pageable pageable) {
        return queryService.list(pageable);
    }

    @GetMapping("/{transactionRef}")
    public TransactionResponse get(@PathVariable String transactionRef) {
        return queryService.getByReference(transactionRef);
    }
}
