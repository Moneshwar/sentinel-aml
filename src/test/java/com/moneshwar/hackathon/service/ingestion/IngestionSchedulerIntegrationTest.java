package com.moneshwar.hackathon.service.ingestion;

import com.moneshwar.hackathon.dto.ingestion.IngestionJobResponse;
import com.moneshwar.hackathon.entity.enums.IngestionJobStatus;
import com.moneshwar.hackathon.repository.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:ingestion-scheduler;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "sentinel.ingestion.worker-enabled=true", "sentinel.ingestion.poll-delay-ms=20"
})
@ActiveProfiles("test")
class IngestionSchedulerIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;
    @Autowired IngestionJobService jobs;
    @Autowired CustomerRepository customers;
    @MockitoSpyBean CustomerIngestionService ingestion;

    @Test void scheduledWorkerReportsProgressWhileHttpRequestHasAlreadyReturned() throws Exception {
        CountDownLatch firstRecord = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> workerThread = new AtomicReference<>();
        doAnswer(call -> {
            workerThread.set(Thread.currentThread().getName());
            IngestionProgress progress = call.getArgument(3);
            IngestionProgress paused = (processed, succeeded, failed) -> {
                progress.update(processed, succeeded, failed);
                if (processed == 1) {
                    firstRecord.countDown();
                    try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Test did not release worker"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                }
            };
            call.getArguments()[3] = paused;
            return call.callRealMethod();
        }).when(ingestion).importCsv(any(InputStream.class), anyString(), anyString(), any(IngestionProgress.class));
        String prefix = UUID.randomUUID().toString();
        var file = new MockMultipartFile("file", "async.csv", "text/csv",
                ("customer_id,first_name,last_name\n" + prefix + "-1,A,B\n" + prefix + "-2,C,D\n").getBytes(StandardCharsets.UTF_8));
        try {
            var response = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build()
                    .perform(multipart("/api/v1/ingestion/customers/csv").file(file).with(user("admin").roles("ADMIN")))
                    .andExpect(status().isAccepted()).andReturn();
            var accepted = mapper.readValue(response.getResponse().getContentAsString(), IngestionJobResponse.class);
            assertTrue(firstRecord.await(5, TimeUnit.SECONDS));
            assertTrue(workerThread.get().startsWith("ingestion-worker-"));
            var running = jobs.get(accepted.jobId());
            assertEquals(IngestionJobStatus.RUNNING, running.status());
            assertEquals(1, running.processed()); assertEquals(1, running.succeeded()); assertNull(running.totalRecords());
            assertTrue(customers.findByCustomerId(prefix + "-1").isPresent());
            assertTrue(customers.findByCustomerId(prefix + "-2").isEmpty());
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            IngestionJobResponse finished;
            do {
                finished = jobs.get(accepted.jobId());
                if (finished.status() == IngestionJobStatus.COMPLETED) break;
                Thread.sleep(20);
            } while (System.nanoTime() < deadline);
            assertEquals(IngestionJobStatus.COMPLETED, finished.status());
            assertEquals(2, finished.succeeded());
        } finally { release.countDown(); }
    }
}
