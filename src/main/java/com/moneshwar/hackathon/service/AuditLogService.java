package com.moneshwar.hackathon.service;

import com.moneshwar.hackathon.entity.AuditLog;
import com.moneshwar.hackathon.entity.enums.AuditAction;
import com.moneshwar.hackathon.repository.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public void record(String entityType, String entityRef, AuditAction action,
                       String fromState, String toState, String actor, String details) {
        auditLogRepository.save(AuditLog.builder()
                .entityType(entityType)
                .entityRef(entityRef)
                .action(action)
                .fromState(fromState)
                .toState(toState)
                .actor(com.moneshwar.hackathon.security.AuditActor.current())
                .details(details)
                .build());
    }
}
