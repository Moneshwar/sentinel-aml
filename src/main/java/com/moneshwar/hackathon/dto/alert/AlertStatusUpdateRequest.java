package com.moneshwar.hackathon.dto.alert;

import com.moneshwar.hackathon.entity.enums.AlertStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AlertStatusUpdateRequest {

    @NotNull
    private AlertStatus status;

    @jakarta.validation.constraints.Size(max = 100)
    private String assignedTo;

    @jakarta.validation.constraints.Size(max = 50)
    private String disposition;

    @jakarta.validation.constraints.Size(max = 4000)
    private String dispositionReason;

    private String actor;
}
