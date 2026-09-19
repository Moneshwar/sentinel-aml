package com.moneshwar.hackathon.repository;

import com.moneshwar.hackathon.entity.IngestionError;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IngestionErrorRepository extends JpaRepository<IngestionError, Long> {

    Page<IngestionError> findByBatchId(String batchId, Pageable pageable);

    Page<IngestionError> findAllByOrderByOccurredAtDesc(Pageable pageable);

    long countByBatchId(String batchId);
}
