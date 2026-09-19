package com.moneshwar.hackathon.service.ingestion;

import com.moneshwar.hackathon.dto.account.AccountRequest;
import com.moneshwar.hackathon.dto.customer.CustomerRequest;
import com.moneshwar.hackathon.dto.ingestion.IngestionJobResponse;
import com.moneshwar.hackathon.entity.ExchangeRate;
import com.moneshwar.hackathon.entity.enums.*;
import com.moneshwar.hackathon.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:ingestion-jobs;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "sentinel.ingestion.max-pending-jobs=2", "sentinel.ingestion.max-batch-records=3",
    "sentinel.ingestion.max-input-bytes=4096"
})
@ActiveProfiles("test")
class IngestionJobsIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired IngestionJobService service;
    @Autowired IngestionJobWorker worker;
    @Autowired IngestionJobRepository jobs;
    @Autowired IngestionJobPayloadRepository payloads;
    @Autowired CustomerRepository customers;
    @Autowired IngestionErrorRepository errors;
    @Autowired TransactionRepository transactions;
    @Autowired AlertRepository alerts;
    @Autowired AuditLogRepository audits;
    @Autowired ExchangeRateRepository rates;
    @Autowired CustomerIngestionService customerService;
    @Autowired AccountIngestionService accountService;
    @Autowired ObjectMapper mapper;
    MockMvc mvc;

    @BeforeEach void setup() {
        payloads.deleteAll(); jobs.deleteAll();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    MockMultipartFile csv(String body) {
        return new MockMultipartFile("file", "customers.csv", "text/csv", body.getBytes(StandardCharsets.UTF_8));
    }

    IngestionJobResponse submit(String body) throws Exception {
        MvcResult response = mvc.perform(multipart("/api/v1/ingestion/customers/csv").file(csv(body))
                .with(user("uploader").roles("ADMIN")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.result").isEmpty()).andReturn();
        var job = mapper.readValue(response.getResponse().getContentAsString(), IngestionJobResponse.class);
        assertEquals(job.statusUrl(), response.getResponse().getHeader("Location"));
        assertEquals(job.jobId(), job.batchId());
        return job;
    }

    @Test void returnsBeforeProcessingAndWorkerCommitsRowsAndErrorsOnAnotherThread() throws Exception {
        String id = "ASYNC-" + UUID.randomUUID();
        var accepted = submit("customer_id,first_name,last_name,annual_income\n" + id + ",Ada,Lovelace,100\nBAD,A,B,not-a-number\n");
        assertFalse(customers.findByCustomerId(id).isPresent());
        assertTrue(payloads.existsById(accepted.jobId()));
        assertEquals("uploader", jobs.findById(accepted.jobId()).orElseThrow().getSubmittedBy());
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(worker::processNext).get(10, TimeUnit.SECONDS);
        }
        var completed = service.get(accepted.jobId());
        assertEquals(IngestionJobStatus.COMPLETED, completed.status());
        assertEquals(2, completed.processed()); assertEquals(1, completed.succeeded()); assertEquals(1, completed.failed());
        assertEquals(2, completed.totalRecords()); assertNotNull(completed.finishedAt());
        assertEquals(1, completed.result().errors().size());
        assertEquals(1, errors.countByBatchId(accepted.jobId()));
        assertTrue(customers.findByCustomerId(id).isPresent());
        assertFalse(payloads.existsById(accepted.jobId()));
        mvc.perform(get(accepted.statusUrl()).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.errors[0].errorType").value("MALFORMED"))
                .andExpect(jsonPath("$.content").doesNotExist());
    }

    @Test void boundedQueueRejectsExcessWorkAndAcceptsAgainAfterCompletion() throws Exception {
        submit("customer_id,first_name,last_name\n");
        submit("customer_id,first_name,last_name\n");
        mvc.perform(multipart("/api/v1/ingestion/customers/csv").file(csv("x"))
                .with(user("admin").roles("ADMIN"))).andExpect(status().isTooManyRequests());
        assertEquals(2, payloads.count());
        worker.processNext();
        submit("customer_id,first_name,last_name\n");
        assertEquals(2, payloads.count());
    }

    @Test void restartFailsInterruptedJobWithoutReplayingAndPreservesQueuedWork() throws Exception {
        var interrupted = submit("customer_id,first_name,last_name\nMUST-NOT-REPLAY,A,B\n");
        var queued = submit("customer_id,first_name,last_name\n");
        jobs.claim(interrupted.jobId(), IngestionJobStatus.QUEUED, IngestionJobStatus.RUNNING, Instant.now());
        jobs.progress(interrupted.jobId(), 7, 5, 2);
        worker.recover();
        var failed = service.get(interrupted.jobId());
        assertEquals(IngestionJobStatus.FAILED, failed.status()); assertEquals(7, failed.processed());
        assertNull(failed.totalRecords()); assertTrue(failed.failureMessage().contains("restart"));
        assertFalse(payloads.existsById(interrupted.jobId()));
        assertEquals(IngestionJobStatus.QUEUED, service.get(queued.jobId()).status());
        worker.processNext();
        assertEquals(IngestionJobStatus.COMPLETED, service.get(queued.jobId()).status());
        assertTrue(customers.findByCustomerId("MUST-NOT-REPLAY").isEmpty());
    }

    @Test void unexpectedWorkerFailureIsTerminalAndDoesNotBlockNextJob() throws Exception {
        var broken = submit("customer_id,first_name,last_name\n");
        var queued = submit("customer_id,first_name,last_name\n");
        var payload = payloads.findById(broken.jobId()).orElseThrow();
        payload.setContent("invalid base64!"); payloads.save(payload);
        worker.processNext();
        assertEquals(IngestionJobStatus.FAILED, service.get(broken.jobId()).status());
        assertFalse(payloads.existsById(broken.jobId()));
        worker.processNext();
        assertEquals(IngestionJobStatus.COMPLETED, service.get(queued.jobId()).status());
    }

    @Test void jobApisRequireAdminAndUnknownJobReturns404() throws Exception {
        mvc.perform(get("/api/v1/ingestion/jobs")).andExpect(status().isUnauthorized());
        for (String role : List.of("VIEWER", "ANALYST")) {
            mvc.perform(get("/api/v1/ingestion/jobs").with(user("reader").roles(role))).andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/ingestion/jobs/missing").with(user("reader").roles(role))).andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/ingestion/customers/batch").contentType("application/json").content("[]")
                    .with(user("reader").roles(role))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/ingestion/jobs/missing").with(user("admin").roles("ADMIN"))).andExpect(status().isNotFound());
    }

    @Test void rejectsOversizedOrEmptyInputWithoutCreatingJobs() throws Exception {
        mvc.perform(multipart("/api/v1/ingestion/customers/csv").file(csv(""))
                .with(user("admin").roles("ADMIN"))).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/ingestion/customers/csv").file(csv("a".repeat(4097)))
                .with(user("admin").roles("ADMIN"))).andExpect(status().isPayloadTooLarge());
        mvc.perform(post("/api/v1/ingestion/customers/batch").contentType("application/json").content("[null,null,null,null]")
                .with(user("admin").roles("ADMIN"))).andExpect(status().isPayloadTooLarge());
        mvc.perform(post("/api/v1/ingestion/customers/batch").contentType("application/json").content("{}")
                .with(user("admin").roles("ADMIN"))).andExpect(status().isBadRequest());
        assertEquals(0, jobs.count()); assertEquals(0, payloads.count());
    }

    @Test void transactionBatchAliasRunsDetectionAndRejectsDuplicateWithoutNewAlert() throws Exception {
        String suffix = UUID.randomUUID().toString();
        CustomerRequest customer = new CustomerRequest(); customer.setCustomerId("C" + suffix);
        customer.setFirstName("Async"); customer.setLastName("Test"); customerService.upsert(customer);
        AccountRequest account = new AccountRequest(); account.setAccountId("A" + suffix);
        account.setCustomerId(customer.getCustomerId()); account.setCurrency("INR"); accountService.upsert(account);
        if (rates.findTopByFromCurrencyAndToCurrencyOrderByEffectiveFromDesc("USD", "INR").isEmpty())
            rates.save(ExchangeRate.builder().fromCurrency("USD").toCurrency("INR").rate(new BigDecimal("83.25")).effectiveFrom(Instant.EPOCH).build());
        String row = """
            {"transactionRef":"T%s","accountId":"A%s","amount":10000,"currency":"USD","transactionTime":"2026-09-01T10:00:00Z"}
            """.formatted(suffix, suffix);
        long previousAlerts = alerts.count();
        var response = mvc.perform(post("/api/v1/transactions/batch").contentType("application/json")
                .content("[" + row + "," + row + ",null]").with(user("uploader").roles("ADMIN")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.totalRecords").value(3)).andReturn();
        String jobId = mapper.readTree(response.getResponse().getContentAsString()).get("jobId").asText();
        assertFalse(transactions.existsByTransactionRef("T" + suffix));
        try (var executor = Executors.newSingleThreadExecutor()) { executor.submit(worker::processNext).get(10, TimeUnit.SECONDS); }
        var result = service.get(jobId);
        assertEquals(IngestionJobStatus.COMPLETED, result.status());
        assertEquals(1, result.succeeded()); assertEquals(2, result.failed());
        assertTrue(transactions.existsByTransactionRef("T" + suffix)); assertEquals(previousAlerts + 1, alerts.count());
        assertTrue(result.result().errors().stream().anyMatch(e -> e.errorType() == IngestionErrorType.DUPLICATE));
        assertTrue(audits.findAll().stream().anyMatch(a -> a.getActor().equals("uploader")));
    }
}
