package com.moneshwar.hackathon.dto.alert;

import com.moneshwar.hackathon.entity.enums.AlertStatus;
import com.moneshwar.hackathon.entity.enums.Severity;

import java.time.Instant;
import java.util.List;

public record AlertResponse(
        Long id,
        String alertRef,
        String customerId,
        String accountId,
        String ruleCode,
        List<String> triggeredRules,
        String disposition,
        String dispositionReason,
        String title,
        String explanation,
        int riskScore,
        Severity severity,
        AlertStatus status,
        String dedupKey,
        String assignedTo,
        List<String> transactionRefs,
        Instant createdAt,
        Instant updatedAt
) {
}
