package com.moneshwar.hackathon.detection.rules;

import com.moneshwar.hackathon.detection.DetectionResult;
import com.moneshwar.hackathon.detection.DetectionRule;
import com.moneshwar.hackathon.detection.RuleConfiguration;
import com.moneshwar.hackathon.detection.TransactionContext;
import com.moneshwar.hackathon.entity.Transaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class StructuringRule implements DetectionRule {

    public static final String CODE = "STRUCTURING";

    @Override
    public String getRuleCode() {
        return CODE;
    }

    @Override
    public String getTitle() {
        return "Structuring / Smurfing";
    }

    @Override
    public DetectionResult evaluate(TransactionContext context, RuleConfiguration configuration) {
        if (!configuration.enabled()) {
            return DetectionResult.notTriggered(CODE);
        }
        String currency = RuleSupport.currency(configuration);
        BigDecimal lower = RuleSupport.positive(configuration, "threshold_lower", "9000");
        BigDecimal upper = RuleSupport.positive(configuration, "threshold_upper", "9999");
        if (lower.compareTo(upper) > 0) {
            throw new IllegalArgumentException("threshold_lower must not exceed threshold_upper");
        }
        if (!within(context, context.transaction(), lower, upper, currency)) {
            return DetectionResult.notTriggered(CODE);
        }
        List<Transaction> matches = RuleSupport.accountWindow(context, configuration, 24).stream()
                .filter(t -> within(context, t, lower, upper, currency)).toList();
        int minimum = RuleSupport.positiveInteger(configuration, "min_transactions", 3);
        if (matches.size() < minimum) {
            return DetectionResult.notTriggered(CODE);
        }
        return DetectionResult.of(CODE, configuration.integer("score", 30), RuleSupport.references(matches),
                matches.size() + " transactions from the same account each fall within " + currency + " "
                        + lower.toPlainString() + "–" + upper.toPlainString() + " (inclusive) in the last "
                        + configuration.integer("window_hours", 24) + " hours; at least " + minimum
                        + " are required. Currency equivalents use the rate effective at each transaction's time."
                        + " This is a repeated just-below-reporting-threshold pattern.");
    }

    private boolean within(TransactionContext context, Transaction transaction, BigDecimal lower,
                           BigDecimal upper, String currency) {
        BigDecimal rate = RuleSupport.rate(context, currency, transaction);
        BigDecimal amount = RuleSupport.amount(context, transaction);
        return amount.compareTo(RuleSupport.normalizedAmount(lower, rate)) >= 0
                && amount.compareTo(RuleSupport.normalizedAmount(upper, rate)) <= 0;
    }
}
