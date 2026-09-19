package com.moneshwar.hackathon.security;

import com.moneshwar.hackathon.dto.common.PageResponse;
import com.moneshwar.hackathon.entity.AuditLog;
import com.moneshwar.hackathon.repository.AuditLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/audit")
@PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
public class AuditController {
    private final AuditLogRepository repository;

    public AuditController(AuditLogRepository repository) {
        this.repository = repository;
    }

    public record AuditResponse(Long id, String entityType, String entityRef, String action,
                                String fromState, String toState, String actor, String details,
                                Instant occurredAt) {
        static AuditResponse from(AuditLog log) {
            return new AuditResponse(log.getId(), log.getEntityType(), log.getEntityRef(), log.getAction().name(),
                    log.getFromState(), log.getToState(), log.getActor(), log.getDetails(), log.getOccurredAt());
        }
    }

    @GetMapping
    public PageResponse<AuditResponse> list(@RequestParam(required = false) String entityRef, Pageable pageable) {
        if (entityRef != null && !entityRef.isBlank()) {
            return PageResponse.from(repository.findByEntityRefOrderByOccurredAtDesc(entityRef, pageable), AuditResponse::from);
        }
        Pageable ordered = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by("occurredAt").descending());
        return PageResponse.from(repository.findAll(ordered), AuditResponse::from);
    }
}
