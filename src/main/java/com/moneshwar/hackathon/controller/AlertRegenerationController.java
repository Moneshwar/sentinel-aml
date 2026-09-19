package com.moneshwar.hackathon.controller;

import com.moneshwar.hackathon.service.AlertRegenerationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/alerts/regenerate")
public class AlertRegenerationController {
    private final AlertRegenerationService service;

    public AlertRegenerationController(AlertRegenerationService service) {
        this.service = service;
    }

    public record Request(@NotNull @Pattern(regexp = "REGENERATE") String confirmation) {}

    @GetMapping
    public AlertRegenerationService.Preview preview() {
        return service.preview();
    }

    @PostMapping
    public AlertRegenerationService.Result regenerate(@Valid @RequestBody Request request) {
        return service.regenerate();
    }
}
