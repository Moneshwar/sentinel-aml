package com.moneshwar.hackathon.controller;

import com.moneshwar.hackathon.dto.cases.CaseCreateRequest;
import com.moneshwar.hackathon.dto.cases.CaseResponse;
import com.moneshwar.hackathon.dto.cases.CaseStatusUpdateRequest;
import com.moneshwar.hackathon.dto.common.PageResponse;
import com.moneshwar.hackathon.service.CaseService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cases")
public class CaseController {

    private final CaseService caseService;

    public CaseController(CaseService caseService) {
        this.caseService = caseService;
    }

    @PostMapping
    public ResponseEntity<CaseResponse> create(@Valid @RequestBody CaseCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(caseService.create(request));
    }

    @GetMapping
    public PageResponse<CaseResponse> list(@RequestParam(required = false) String status, Pageable pageable) {
        return caseService.list(status, pageable);
    }

    @GetMapping("/{caseRef}")
    public CaseResponse get(@PathVariable String caseRef) {
        return caseService.getByCaseRef(caseRef);
    }

    @PatchMapping("/{caseRef}/status")
    public CaseResponse updateStatus(@PathVariable String caseRef,
                                     @Valid @RequestBody CaseStatusUpdateRequest request) {
        return caseService.updateStatus(caseRef, request);
    }
}
