package com.moneshwar.hackathon.service;

import com.moneshwar.hackathon.dto.cases.CaseCreateRequest;
import com.moneshwar.hackathon.dto.cases.CaseResponse;
import com.moneshwar.hackathon.dto.cases.CaseStatusUpdateRequest;
import com.moneshwar.hackathon.dto.common.PageResponse;
import com.moneshwar.hackathon.entity.Alert;
import com.moneshwar.hackathon.entity.Case;
import com.moneshwar.hackathon.entity.Customer;
import com.moneshwar.hackathon.entity.enums.AuditAction;
import com.moneshwar.hackathon.entity.enums.CaseStatus;
import com.moneshwar.hackathon.entity.enums.Severity;
import com.moneshwar.hackathon.exception.ResourceNotFoundException;
import com.moneshwar.hackathon.mapper.CaseMapper;
import com.moneshwar.hackathon.repository.AlertRepository;
import com.moneshwar.hackathon.repository.CaseRepository;
import com.moneshwar.hackathon.repository.CustomerRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;

import java.time.Instant;
import java.time.Year;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.UUID;

@Service
public class CaseService {

    private final CaseRepository caseRepository;
    private final AlertRepository alertRepository;
    private final CustomerRepository customerRepository;
    private final CaseMapper caseMapper;
    private final AuditLogService auditLogService;

    public CaseService(CaseRepository caseRepository,
                       AlertRepository alertRepository,
                       CustomerRepository customerRepository,
                       CaseMapper caseMapper,
                       AuditLogService auditLogService) {
        this.caseRepository = caseRepository;
        this.alertRepository = alertRepository;
        this.customerRepository = customerRepository;
        this.caseMapper = caseMapper;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public PageResponse<CaseResponse> list(String status, Pageable pageable) {
        if (status != null && !status.isBlank()) {
            CaseStatus caseStatus = CaseStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
            return PageResponse.from(caseRepository.findByStatus(caseStatus, pageable), caseMapper::toListResponse);
        }
        return PageResponse.from(caseRepository.findAll(pageable), caseMapper::toListResponse);
    }

    @Transactional(readOnly = true)
    public CaseResponse getByCaseRef(String caseRef) {
        Case aCase = find(caseRef);
        return com.moneshwar.hackathon.security.PrivacyAccess.canReadDetails()
                ? caseMapper.toResponse(aCase) : caseMapper.toListResponse(aCase);
    }

    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    public CaseResponse create(CaseCreateRequest request) {
        Case newCase = new Case();
        newCase.setCaseRef(nextCaseRef());
        newCase.setTitle(request.getTitle());
        newCase.setDescription(request.getDescription());
        newCase.setPriority(request.getPriority() != null ? request.getPriority() : Severity.MEDIUM);
        newCase.setAssignedTo(request.getAssignedTo());
        newCase.setStatus(CaseStatus.OPEN);
        newCase.setOpenedAt(Instant.now());

        if (request.getCustomerRef() != null) {
            Customer customer = customerRepository.findByCustomerId(request.getCustomerRef())
                    .orElseThrow(() -> new ResourceNotFoundException("Customer", request.getCustomerRef()));
            newCase.setCustomer(customer);
        }
        if (request.getAlertRefs() != null && !request.getAlertRefs().isEmpty()) {
            LinkedHashSet<Alert> alerts = new LinkedHashSet<>();
            for (String alertRef : request.getAlertRefs()) {
                Alert alert = alertRepository.findByAlertRef(alertRef)
                        .orElseThrow(() -> new ResourceNotFoundException("Alert", alertRef));
                if (alert.getCustomer() == null) {
                    throw new IllegalArgumentException("Linked alerts must identify a customer");
                }
                if (newCase.getCustomer() == null) {
                    newCase.setCustomer(alert.getCustomer());
                } else if (!newCase.getCustomer().getId().equals(alert.getCustomer().getId())) {
                    throw new IllegalArgumentException("All linked alerts must belong to the case customer");
                }
                alerts.add(alert);
            }
            newCase.setAlerts(alerts);
        }

        Case saved = caseRepository.save(newCase);
        auditLogService.record("CASE", saved.getCaseRef(), AuditAction.CREATED,
                null, saved.getStatus().name(), request.getActor(), "Case opened");
        return caseMapper.toResponse(saved);
    }

    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    public CaseResponse updateStatus(String caseRef, CaseStatusUpdateRequest request) {
        Case existing = find(caseRef);
        if (existing.getStatus() == CaseStatus.CLOSED || existing.getStatus() == CaseStatus.SAR_FILED) {
            throw new IllegalArgumentException("A disposed case cannot be changed");
        }
        if (request.getStatus() == null) {
            throw new IllegalArgumentException("Case status is required");
        }
        boolean terminal = request.getStatus() == CaseStatus.CLOSED || request.getStatus() == CaseStatus.SAR_FILED;
        if (terminal && (request.getDispositionReason() == null || request.getDispositionReason().isBlank())) {
            throw new IllegalArgumentException("A disposition reason is required to close a case or file a SAR");
        }
        String fromState = existing.getStatus().name();
        existing.setStatus(request.getStatus());
        if (request.getDisposition() != null || terminal) {
            existing.setDisposition(request.getDisposition() == null || request.getDisposition().isBlank()
                    ? request.getStatus().name() : request.getDisposition().trim());
        }
        if (request.getDispositionReason() != null) {
            existing.setDispositionReason(request.getDispositionReason().trim());
        }
        if (request.getAssignedTo() != null) {
            existing.setAssignedTo(request.getAssignedTo());
        }
        if (request.getStatus() == CaseStatus.CLOSED || request.getStatus() == CaseStatus.SAR_FILED) {
            existing.setClosedAt(Instant.now());
        }
        Case saved = caseRepository.save(existing);
        auditLogService.record("CASE", caseRef, AuditAction.STATUS_CHANGED,
                fromState, request.getStatus().name(), request.getActor(), request.getDispositionReason());
        return caseMapper.toResponse(saved);
    }

    public Case find(String caseRef) {
        return caseRepository.findByCaseRef(caseRef)
                .orElseThrow(() -> new ResourceNotFoundException("Case", caseRef));
    }

    private String nextCaseRef() {
        return "CASE-" + Year.now() + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
