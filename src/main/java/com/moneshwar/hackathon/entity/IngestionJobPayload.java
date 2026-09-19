package com.moneshwar.hackathon.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Kept separately so status polling never loads the uploaded input. */
@Entity
@Table(name = "ingestion_job_payloads")
@Getter @Setter
public class IngestionJobPayload {
    @Id @Column(length = 64) private String jobId;
    @Column(nullable = false, columnDefinition = "TEXT") private String content;
}
