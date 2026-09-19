package com.moneshwar.hackathon.dto.cases;

import com.moneshwar.hackathon.entity.enums.CaseStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CaseStatusUpdateRequest {

    @NotNull
    private CaseStatus status;

    @jakarta.validation.constraints.Size(max = 50)
    private String disposition;

    @jakarta.validation.constraints.Size(max = 4000)
    private String dispositionReason;

    @jakarta.validation.constraints.Size(max = 100)
    private String assignedTo;

    private String actor;
}
