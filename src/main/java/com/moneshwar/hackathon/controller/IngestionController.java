package com.moneshwar.hackathon.controller;

import com.moneshwar.hackathon.dto.account.AccountRequest;
import com.moneshwar.hackathon.dto.common.PageResponse;
import com.moneshwar.hackathon.dto.customer.CustomerRequest;
import com.moneshwar.hackathon.dto.ingestion.*;
import com.moneshwar.hackathon.dto.transaction.TransactionRequest;
import com.moneshwar.hackathon.entity.enums.IngestionEntityType;
import com.moneshwar.hackathon.service.ingestion.IngestionErrorQueryService;
import com.moneshwar.hackathon.service.ingestion.IngestionJobService;
import org.springframework.data.domain.Pageable;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1/ingestion")
public class IngestionController {
    private final IngestionJobService jobs;
    private final IngestionErrorQueryService errors;

    public IngestionController(IngestionJobService jobs, IngestionErrorQueryService errors) {
        this.jobs = jobs; this.errors = errors;
    }

    @PostMapping(value = "/customers/csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IngestionJobResponse> importCustomersCsv(@RequestParam("file") MultipartFile file) throws IOException {
        return accepted(jobs.submitCsv(IngestionEntityType.CUSTOMER, file));
    }

    @PostMapping(value = "/accounts/csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IngestionJobResponse> importAccountsCsv(@RequestParam("file") MultipartFile file) throws IOException {
        return accepted(jobs.submitCsv(IngestionEntityType.ACCOUNT, file));
    }

    @PostMapping(value = "/transactions/csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IngestionJobResponse> importTransactionsCsv(@RequestParam("file") MultipartFile file) throws IOException {
        return accepted(jobs.submitCsv(IngestionEntityType.TRANSACTION, file));
    }

    @PostMapping("/customers/batch")
    public ResponseEntity<IngestionJobResponse> customersBatch(@RequestBody List<CustomerRequest> requests) {
        return accepted(jobs.submitBatch(IngestionEntityType.CUSTOMER, requests));
    }

    @PostMapping("/accounts/batch")
    public ResponseEntity<IngestionJobResponse> accountsBatch(@RequestBody List<AccountRequest> requests) {
        return accepted(jobs.submitBatch(IngestionEntityType.ACCOUNT, requests));
    }

    @PostMapping("/transactions/batch")
    public ResponseEntity<IngestionJobResponse> transactionsBatch(@RequestBody List<TransactionRequest> requests) {
        return accepted(jobs.submitBatch(IngestionEntityType.TRANSACTION, requests));
    }

    @GetMapping("/jobs/{id}")
    public IngestionJobResponse job(@PathVariable String id) { return jobs.get(id); }

    @GetMapping("/jobs")
    public PageResponse<IngestionJobResponse> jobs(Pageable pageable) { return jobs.list(pageable); }

    @GetMapping("/errors")
    public PageResponse<IngestionErrorResponse> errors(@RequestParam(required = false) String batchId, Pageable pageable) {
        return errors.list(batchId, pageable);
    }

    public static ResponseEntity<IngestionJobResponse> accepted(IngestionJobResponse job) {
        return ResponseEntity.accepted().location(URI.create(job.statusUrl())).body(job);
    }
}
