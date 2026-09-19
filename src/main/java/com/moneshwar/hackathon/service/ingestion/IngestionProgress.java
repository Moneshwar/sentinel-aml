package com.moneshwar.hackathon.service.ingestion;

/** Called after each record has committed or its rejection has been recorded. */
@FunctionalInterface
public interface IngestionProgress {
    IngestionProgress NONE = (processed, succeeded, failed) -> { };
    void update(int processed, int succeeded, int failed);
}
