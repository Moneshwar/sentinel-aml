package com.moneshwar.hackathon.dto.cases;

import com.moneshwar.hackathon.entity.enums.Severity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CaseCreateRequest {

    private String customerRef;

    @NotBlank
    @Size(max = 255)
    private String title;

    private String description;

    private Severity priority;

    @Size(max = 100)
    private String assignedTo;

    private List<String> alertRefs;

    @Size(max = 100)
    private String actor;
}
