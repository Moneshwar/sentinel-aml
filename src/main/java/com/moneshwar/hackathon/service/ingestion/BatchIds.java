package com.moneshwar.hackathon.service.ingestion;

import java.time.LocalDate;
import java.util.UUID;

public final class BatchIds {

    private BatchIds() {
    }

    public static String next() {
        return "BATCH-" + LocalDate.now() + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
