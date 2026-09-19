package com.moneshwar.hackathon.detection;

import com.moneshwar.hackathon.entity.Transaction;

/**
 * A single, independently-testable AML detection rule. Rules must NOT create
 * alerts or touch the database — they inspect the {@link TransactionContext}
 * and the rule's own {@link RuleConfiguration} and return a
 * {@link DetectionResult}. The {@link DetectionEngine} aggregates results,
 * computes risk scores and performs alert deduplication/aggregation.
 */
public interface DetectionRule {

    /**
     * Stable rule code persisted in rule_config (e.g. "CTR", "STRUCTURING").
     */
    String getRuleCode();

    /**
     * Human-readable title used on alerts, e.g. "Cash Transaction Threshold".
     */
    String getTitle();

    /** Mandatory regulatory checks run even if a stored configuration is disabled. */
    default boolean isMandatory() {
        return false;
    }

    /** Whether an equal-time event must be revisited after another event arrives. */
    default boolean requiresSameTimestampReplay(Transaction incoming, Transaction previous) {
        return false;
    }

    /**
     * Evaluate the transaction in the context of the supplied configuration.
     */
    DetectionResult evaluate(TransactionContext context, RuleConfiguration configuration);
}
