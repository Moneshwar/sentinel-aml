package com.moneshwar.hackathon.entity;

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

/**
 * Configurable high-risk / sanctioned jurisdictions used by the
 * HIGH_RISK_JURISDICTION rule. Add/remove at runtime via the admin API.
 */
@Entity
@Table(name = "high_risk_jurisdictions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HighRiskJurisdiction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "country_code", nullable = false, length = 3)
    private String countryCode;

    @Column(length = 255)
    private String description;

    @Column(name = "is_sanctioned", nullable = false)
    @Builder.Default
    private boolean sanctioned = false;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @PrePersist
    void onCreate() {
        if (effectiveFrom == null) {
            effectiveFrom = Instant.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        if (effectiveTo != null && effectiveFrom != null && effectiveTo.isBefore(effectiveFrom)) {
            effectiveFrom = effectiveTo;
        }
    }
}