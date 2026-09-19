package com.moneshwar.hackathon.service.ingestion;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "sentinel.ingestion.worker-enabled", havingValue = "true", matchIfMissing = true)
public class IngestionSchedulingConfiguration {
    @Bean
    ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ingestion-worker-");
        // Early shutdown lets Spring stop scheduled tasks before closing persistence.
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    @Bean
    JobPoller ingestionJobPoller(IngestionJobWorker worker) { return new JobPoller(worker); }

    public static class JobPoller {
        private final IngestionJobWorker worker;
        JobPoller(IngestionJobWorker worker) { this.worker = worker; }
        @Scheduled(fixedDelayString = "${sentinel.ingestion.poll-delay-ms:500}")
        public void poll() { worker.poll(); }
    }
}
