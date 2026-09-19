package com.moneshwar.hackathon.detection.rules;

import com.moneshwar.hackathon.detection.DetectionResult;
import com.moneshwar.hackathon.detection.DetectionRule;
import com.moneshwar.hackathon.detection.RuleConfiguration;
import com.moneshwar.hackathon.detection.TransactionContext;
import com.moneshwar.hackathon.entity.Transaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Component
public class RoundNumberRule implements DetectionRule {

    public static final String CODE = "ROUND_NUMBER";

    @Override
    public String getRuleCode() {
        return CODE;
    }

    @Override
    public String getTitle() {
        return "Repeated Round-Number Transactions";
    }

    @Override
    public DetectionResult evaluate(TransactionContext context, RuleConfiguration configuration) {
        if (!configuration.enabled()) {
            return DetectionResult.notTriggered(CODE);
        }
        String currency = RuleSupport.currency(configuration);
        BigDecimal interval = RuleSupport.positive(configuration, "round_interval", "1000");
        if (!round(context, context.transaction(), interval, currency)) {
            return DetectionResult.notTriggered(CODE);
        }
        List<Transaction> matches = RuleSupport.accountWindow(context, configuration, 24).stream()
                .filter(t -> round(context, t, interval, currency)).toList();
        int minimum = RuleSupport.positiveInteger(configuration, "min_transactions", 3);
        if (matches.size() < minimum) {
            return DetectionResult.notTriggered(CODE);
        }
        return DetectionResult.of(CODE, configuration.integer("score", 10), RuleSupport.references(matches),
                matches.size() + " transactions from the same account are positive exact multiples of "
                        + currency + " " + interval.toPlainString() + " within "
                        + configuration.integer("window_hours", 24) + " hours; at least " + minimum
                        + " are required. Currency equivalents use the rate effective at each transaction's time."
                        + " Just-below-threshold patterns are evaluated by the structuring rule.");
    }

    private boolean round(TransactionContext context, Transaction transaction, BigDecimal interval, String currency) {
        BigDecimal amount = RuleSupport.amount(context, transaction);
        BigDecimal rate = RuleSupport.rate(context, currency, transaction);
        BigDecimal intervalBase = interval.multiply(rate);
        BigDecimal multiples = amount.divide(intervalBase, 0, RoundingMode.HALF_UP);
        return amount.signum() > 0 && multiples.signum() > 0
                && amount.compareTo(RuleSupport.normalizedAmount(interval.multiply(multiples), rate)) == 0;
    }
}
