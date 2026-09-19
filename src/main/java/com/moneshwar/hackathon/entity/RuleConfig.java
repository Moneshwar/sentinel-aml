package com.moneshwar.hackathon.entity;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-rule runtime configuration. Stored as a TEXT JSON blob so rules can be
 * toggled/tuned at runtime (admin API or SQL) without a code deployment.
 */
@Entity
@Table(name = "rule_config")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleConfig {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_code", nullable = false, unique = true, length = 50)
    private String ruleCode;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(columnDefinition = "TEXT", nullable = false)
    @Builder.Default
    private String configuration = "{}";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Map<String, Object> configurationMap() {
        try {
            Map<String, Object> parsed = MAPPER.readValue(configuration, new TypeReference<>() {});
            return parsed == null ? new HashMap<>() : parsed;
        } catch (Exception ex) {
            return new HashMap<>();
        }
    }

    public void setConfigurationMap(Map<String, Object> configuration) {
        try {
            this.configuration = MAPPER.writeValueAsString(configuration == null ? Map.of() : configuration);
        } catch (Exception ex) {
            this.configuration = "{}";
        }
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
