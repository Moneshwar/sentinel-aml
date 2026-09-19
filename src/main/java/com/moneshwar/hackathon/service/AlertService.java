package com.moneshwar.hackathon.service;

import com.moneshwar.hackathon.dto.alert.AlertResponse;
import com.moneshwar.hackathon.dto.alert.AlertStatusUpdateRequest;
import com.moneshwar.hackathon.dto.common.PageResponse;
import com.moneshwar.hackathon.entity.Alert;
import com.moneshwar.hackathon.entity.enums.AlertStatus;
import com.moneshwar.hackathon.entity.enums.AuditAction;
import com.moneshwar.hackathon.exception.ResourceNotFoundException;
import com.moneshwar.hackathon.mapper.AlertMapper;
import com.moneshwar.hackathon.repository.AlertRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.Locale;

@Service
public class AlertService {

    private final AlertRepository alertRepository;
    private final AlertMapper alertMapper;
    private final AuditLogService auditLogService;

    public AlertService(AlertRepository alertRepository, AlertMapper alertMapper, AuditLogService auditLogService) {
        this.alertRepository = alertRepository;
        this.alertMapper = alertMapper;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public PageResponse<AlertResponse> list(String status, Pageable pageable) {
        if (status != null && !status.isBlank()) {
            AlertStatus alertStatus = AlertStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
            return PageResponse.from(alertRepository.findByStatusOrderByRiskScoreDesc(alertStatus, pageable), alertMapper::toListResponse);
        }
        return PageResponse.from(alertRepository.findAllByOrderByRiskScoreDesc(pageable), alertMapper::toListResponse);
    }

    @Transactional(readOnly = true)
    public AlertResponse getByAlertRef(String alertRef) {
        Alert alert = find(alertRef);
        return com.moneshwar.hackathon.security.PrivacyAccess.canReadDetails()
                ? alertMapper.toResponse(alert) : alertMapper.toListResponse(alert);
    }

    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    public AlertResponse updateStatus(String alertRef, AlertStatusUpdateRequest request) {
        Alert alert = find(alertRef);
        if (alert.getStatus() == AlertStatus.CLEARED || alert.getStatus() == AlertStatus.CLOSED) {
            throw new IllegalArgumentException("A disposed alert cannot be changed");
        }
        if (request.getStatus() == null) {
            throw new IllegalArgumentException("Alert status is required");
        }
        boolean terminal = request.getStatus() == AlertStatus.CLEARED || request.getStatus() == AlertStatus.CLOSED;
        if (terminal && (request.getDispositionReason() == null || request.getDispositionReason().isBlank())) {
            throw new IllegalArgumentException("A disposition reason is required to clear or close an alert");
        }
        String fromState = alert.getStatus().name();
        alert.setStatus(request.getStatus());
        if (request.getDisposition() != null || terminal) {
            alert.setDisposition(request.getDisposition() == null || request.getDisposition().isBlank()
                    ? request.getStatus().name() : request.getDisposition().trim());
        }
        if (request.getDispositionReason() != null) {
            alert.setDispositionReason(request.getDispositionReason().trim());
        }
        if (request.getAssignedTo() != null) {
            alert.setAssignedTo(request.getAssignedTo());
        }
        Alert saved = alertRepository.save(alert);
        auditLogService.record("ALERT", alertRef, AuditAction.STATUS_CHANGED,
                fromState, request.getStatus().name(), null, request.getDispositionReason());
        return alertMapper.toResponse(saved);
    }

    public Alert find(String alertRef) {
        return alertRepository.findByAlertRef(alertRef)
                .orElseThrow(() -> new ResourceNotFoundException("Alert", alertRef));
    }
}
