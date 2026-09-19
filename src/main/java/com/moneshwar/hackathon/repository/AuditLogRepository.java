package com.moneshwar.hackathon.repository;

import com.moneshwar.hackathon.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    Page<AuditLog> findByEntityTypeAndEntityRefOrderByOccurredAtDesc(String entityType, String entityRef, Pageable pageable);

    Page<AuditLog> findByEntityRefOrderByOccurredAtDesc(String entityRef, Pageable pageable);
}
