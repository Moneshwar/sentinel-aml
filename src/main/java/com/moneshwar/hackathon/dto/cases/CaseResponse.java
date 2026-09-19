package com.moneshwar.hackathon.dto.cases;

import com.moneshwar.hackathon.entity.enums.CaseStatus;
import com.moneshwar.hackathon.entity.enums.Severity;

import java.time.Instant;
import java.util.List;

public record CaseResponse(
        Long id,
        String caseRef,
        String customerId,
        String title,
        String description,
        CaseStatus status,
        Severity priority,
        String assignedTo,
        String disposition,
        String dispositionReason,
        List<String> alertRefs,
        Instant openedAt,
        Instant closedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
