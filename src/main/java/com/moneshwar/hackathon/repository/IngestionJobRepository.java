package com.moneshwar.hackathon.repository;

import com.moneshwar.hackathon.entity.IngestionJob;
import com.moneshwar.hackathon.entity.enums.IngestionJobStatus;
import org.springframework.data.jpa.repository.*;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

public interface IngestionJobRepository extends JpaRepository<IngestionJob, String> {
    Optional<IngestionJob> findFirstByStatusOrderByCreatedAtAscIdAsc(IngestionJobStatus status);
    List<IngestionJob> findByStatus(IngestionJobStatus status);
    long countByStatusIn(Collection<IngestionJobStatus> statuses);

    @Modifying @Transactional
    @Query("update IngestionJob j set j.status = :running, j.startedAt = :now where j.id = :id and j.status = :queued")
    int claim(String id, IngestionJobStatus queued, IngestionJobStatus running, Instant now);

    @Modifying @Transactional
    @Query("update IngestionJob j set j.processed = :processed, j.succeeded = :succeeded, j.failed = :failed where j.id = :id")
    void progress(String id, int processed, int succeeded, int failed);
}
