package com.moneshwar.hackathon.entity;

import com.moneshwar.hackathon.entity.enums.IngestionEntityType;
import com.moneshwar.hackathon.entity.enums.IngestionErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "ingestion_errors")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestionError {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", length = 64)
    private String batchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 30)
    private IngestionEntityType entityType;

    @Column(name = "source_name")
    private String sourceName;

    @Column(name = "source_reference", length = 255)
    private String sourceReference;

    @Column(name = "raw_record", columnDefinition = "TEXT")
    private String rawRecord;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_type", nullable = false, length = 40)
    private IngestionErrorType errorType;

    @Column(name = "error_message", nullable = false, length = 1000)
    private String errorMessage;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @PrePersist
    void onCreate() {
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
    }
}
