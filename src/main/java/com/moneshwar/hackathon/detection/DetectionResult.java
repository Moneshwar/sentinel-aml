package com.moneshwar.hackathon.detection;

import java.util.List;

/**
 * Outcome of evaluating a single rule against a transaction.
 *
 * @param ruleCode      the rule that produced this result
 * @param triggered     true if the pattern matched
 * @param score         rule score (0 when not triggered), already read from config
 * @param transactionIds evidence transaction references that support the finding
 * @param explanation   plain-language reasoning shown to analysts
 */
public record DetectionResult(
        String ruleCode,
        boolean triggered,
        int score,
        List<String> transactionIds,
        String explanation
) {

    public DetectionResult {
        score = triggered ? Math.max(0, Math.min(100, score)) : 0;
        transactionIds = List.copyOf(transactionIds);
    }

    public static DetectionResult notTriggered(String ruleCode) {
        return new DetectionResult(ruleCode, false, 0, List.of(), "");
    }

    public static DetectionResult of(String ruleCode, int score, List<String> transactionIds, String explanation) {
        return new DetectionResult(ruleCode, true, score, transactionIds, explanation);
    }
}
