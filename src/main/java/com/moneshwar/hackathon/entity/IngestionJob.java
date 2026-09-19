package com.moneshwar.hackathon.entity;

import com.moneshwar.hackathon.entity.enums.IngestionEntityType;
import com.moneshwar.hackathon.entity.enums.IngestionJobStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "ingestion_jobs")
@Getter @Setter
public class IngestionJob {
    @Id @Column(length = 64) private String id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private IngestionEntityType entityType;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private IngestionJobStatus status;
    @Column(nullable = false, length = 10) private String format;
    @Column(length = 255) private String sourceName;
    @Column(nullable = false, length = 255) private String submittedBy;
    private Integer totalRecords;
    @Column(nullable = false) private int processed;
    @Column(nullable = false) private int succeeded;
    @Column(nullable = false) private int failed;
    @Column(nullable = false) private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;
    @Column(length = 1000) private String failureMessage;
}
