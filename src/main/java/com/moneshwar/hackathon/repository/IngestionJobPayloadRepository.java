package com.moneshwar.hackathon.repository;

import com.moneshwar.hackathon.entity.IngestionJobPayload;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestionJobPayloadRepository extends JpaRepository<IngestionJobPayload, String> { }
