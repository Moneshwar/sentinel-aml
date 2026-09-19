package com.moneshwar.hackathon.detection.rules;

import com.moneshwar.hackathon.detection.DetectionResult;
import com.moneshwar.hackathon.detection.DetectionRule;
import com.moneshwar.hackathon.detection.RuleConfiguration;
import com.moneshwar.hackathon.detection.TransactionContext;
import com.moneshwar.hackathon.entity.Transaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Every transaction at or above the currency-adjusted reporting threshold is flagged.
 *
 * Config: threshold_amount (default 10000), currency (default USD), score (default 20).
 */
@Component
public class CtrRule implements DetectionRule {

    public static final String CODE = "CTR";

    private static final int DEFAULT_SCORE = 20;

    @Override
    public String getRuleCode() {
        return CODE;
    }

    @Override
    public String getTitle() {
        return "Cash Transaction Threshold";
    }

    @Override
    public boolean isMandatory() {
        return true;
    }

    @Override
    public DetectionResult evaluate(TransactionContext context, RuleConfiguration configuration) {
        String currency = RuleSupport.currency(configuration);
        BigDecimal threshold = RuleSupport.positive(configuration, "threshold_amount", "10000");
        BigDecimal configuredThresholdBase = RuleSupport.normalizedAmount(threshold, RuleSupport.rate(context, currency));
        BigDecimal mandatoryMaximumBase = RuleSupport.normalizedAmount(new BigDecimal("10000"), RuleSupport.rate(context, "USD"));
        BigDecimal thresholdBase = configuredThresholdBase.min(mandatoryMaximumBase);
        int score = configuration.integer("score", DEFAULT_SCORE);

        Transaction tx = context.transaction();
        BigDecimal value = RuleSupport.amount(context, tx);

        if (value.compareTo(thresholdBase) >= 0) {
            String explanation = "Single " + context.baseCurrency() + " transaction "
                    + RuleSupport.reference(tx) + " of " + value.toPlainString()
                    + " meets/exceeds the effective reporting threshold " + context.baseCurrency() + " "
                    + thresholdBase.toPlainString() + " (configured " + currency + " " + threshold.toPlainString()
                    + "; mandatory maximum USD 10000 = " + context.baseCurrency() + " "
                    + mandatoryMaximumBase.toPlainString() + "). Configuration can lower this threshold"
                    + " but cannot exceed the mandatory maximum.";
            return DetectionResult.of(CODE, score, List.of(RuleSupport.reference(tx)), explanation);
        }
        return DetectionResult.notTriggered(CODE);
    }

}
