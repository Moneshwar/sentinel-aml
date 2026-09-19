package com.moneshwar.hackathon.service.ingestion;

import com.moneshwar.hackathon.dto.account.AccountRequest;
import com.moneshwar.hackathon.dto.customer.CustomerRequest;
import com.moneshwar.hackathon.dto.transaction.TransactionRequest;
import com.moneshwar.hackathon.dto.ingestion.IngestionResult;
import com.moneshwar.hackathon.entity.IngestionJob;
import com.moneshwar.hackathon.entity.enums.*;
import com.moneshwar.hackathon.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.*;

/** One worker per application. Deploy a single application instance against this queue. */
@Component
public class IngestionJobWorker {
    private static final Logger log = LoggerFactory.getLogger(IngestionJobWorker.class);
    private final IngestionJobRepository jobs;
    private final IngestionJobPayloadRepository payloads;
    private final IngestionJobService service;
    private final CustomerIngestionService customers;
    private final AccountIngestionService accounts;
    private final TransactionIngestionService transactions;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private volatile boolean ready;

    public IngestionJobWorker(IngestionJobRepository jobs, IngestionJobPayloadRepository payloads,
            IngestionJobService service, CustomerIngestionService customers, AccountIngestionService accounts,
            TransactionIngestionService transactions, ObjectMapper mapper,
            @Value("${sentinel.ingestion.worker-enabled:true}") boolean enabled) {
        this.jobs = jobs; this.payloads = payloads; this.service = service; this.customers = customers;
        this.accounts = accounts; this.transactions = transactions; this.mapper = mapper; this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedJobs() {
        if (!enabled) return;
        recover();
        ready = true;
    }

    public synchronized void recover() {
        for (IngestionJob job : jobs.findByStatus(IngestionJobStatus.RUNNING)) {
            service.finish(job.getId(), null, "Processing was interrupted by a restart. Previously committed records remain; review them before submitting again. Counts reflect the last saved progress.");
        }
    }

    @EventListener(org.springframework.context.event.ContextClosedEvent.class)
    public void stop() { ready = false; }

    public void poll() {
        if (enabled && ready) processNext();
    }

    public synchronized void processNext() {
        var queued = jobs.findFirstByStatusOrderByCreatedAtAscIdAsc(IngestionJobStatus.QUEUED);
        if (queued.isEmpty()) return;
        IngestionJob job = queued.get();
        if (jobs.claim(job.getId(), IngestionJobStatus.QUEUED, IngestionJobStatus.RUNNING, Instant.now()) != 1) return;
        var previousContext = org.springframework.security.core.context.SecurityContextHolder.getContext();
        var jobContext = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        jobContext.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                .authenticated(job.getSubmittedBy(), null, List.of()));
        org.springframework.security.core.context.SecurityContextHolder.setContext(jobContext);
        try {
            byte[] bytes = Base64.getDecoder().decode(payloads.findById(job.getId()).orElseThrow().getContent());
            IngestionProgress progress = (processed, succeeded, failed) -> {
                jobs.progress(job.getId(), processed, succeeded, failed);
                if ((enabled && !ready) || Thread.currentThread().isInterrupted()) throw new IllegalStateException("Worker interrupted");
            };
            IngestionResult result;
            if (job.getFormat().equals("CSV")) {
                try (var input = new ByteArrayInputStream(bytes)) {
                    result = switch (job.getEntityType()) {
                        case CUSTOMER -> customers.importCsv(input, job.getSourceName(), job.getId(), progress);
                        case ACCOUNT -> accounts.importCsv(input, job.getSourceName(), job.getId(), progress);
                        case TRANSACTION -> transactions.importCsv(input, job.getSourceName(), job.getId(), progress);
                    };
                }
            } else {
                result = switch (job.getEntityType()) {
                    case CUSTOMER -> customers.ingestBatch(mapper.readValue(bytes, new TypeReference<List<CustomerRequest>>() {}), IngestionSource.BATCH, job.getId(), progress);
                    case ACCOUNT -> accounts.ingestBatch(mapper.readValue(bytes, new TypeReference<List<AccountRequest>>() {}), IngestionSource.BATCH, job.getId(), progress);
                    case TRANSACTION -> transactions.ingestBatch(mapper.readValue(bytes, new TypeReference<List<TransactionRequest>>() {}), IngestionSource.BATCH, job.getId(), progress);
                };
            }
            service.finish(job.getId(), result, null);
        } catch (Exception failure) {
            log.error("Ingestion job {} failed", job.getId(), failure);
            service.finish(job.getId(), null, "Import stopped unexpectedly. Previously committed records remain; review progress and errors before submitting again.");
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.setContext(previousContext);
        }
    }
}
