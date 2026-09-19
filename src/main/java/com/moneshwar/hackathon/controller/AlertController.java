package com.moneshwar.hackathon.controller;

import com.moneshwar.hackathon.dto.alert.AlertResponse;
import com.moneshwar.hackathon.dto.alert.AlertStatusUpdateRequest;
import com.moneshwar.hackathon.dto.common.PageResponse;
import com.moneshwar.hackathon.service.AlertService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping
    public PageResponse<AlertResponse> list(@RequestParam(required = false) String status, Pageable pageable) {
        return alertService.list(status, pageable);
    }

    @GetMapping("/{alertRef}")
    public AlertResponse get(@PathVariable String alertRef) {
        return alertService.getByAlertRef(alertRef);
    }

    @PatchMapping("/{alertRef}/status")
    public AlertResponse updateStatus(@PathVariable String alertRef,
                                      @Valid @RequestBody AlertStatusUpdateRequest request) {
        return alertService.updateStatus(alertRef, request);
    }
}
