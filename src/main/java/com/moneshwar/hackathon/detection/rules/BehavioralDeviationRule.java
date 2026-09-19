package com.moneshwar.hackathon.detection.rules;

import com.moneshwar.hackathon.detection.DetectionResult;
import com.moneshwar.hackathon.detection.DetectionRule;
import com.moneshwar.hackathon.detection.RuleConfiguration;
import com.moneshwar.hackathon.detection.TransactionContext;
import com.moneshwar.hackathon.entity.Transaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
public class BehavioralDeviationRule implements DetectionRule {

    public static final String CODE = "BEHAVIORAL_DEVIATION";

    @Override
    public String getRuleCode() {
        return CODE;
    }

    @Override
    public String getTitle() {
        return "Unusual Volume / Behavioral Deviation";
    }

    @Override
    public DetectionResult evaluate(TransactionContext context, RuleConfiguration configuration) {
        if (!configuration.enabled()) {
            return DetectionResult.notTriggered(CODE);
        }
        int days = RuleSupport.positiveInteger(configuration, "rolling_days", 90);
        BigDecimal multiplier = RuleSupport.positive(configuration, "multiplier", "3");
        Instant end = context.transaction().getTransactionTime();
        Instant todayStart = end.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant historyStart = todayStart.minus(days, ChronoUnit.DAYS);
        List<Transaction> history = RuleSupport.window(context.customerTransactions(), historyStart, end).stream()
                .filter(t -> t.getTransactionTime().isBefore(todayStart)).toList();
        if (history.isEmpty()) {
            return DetectionResult.notTriggered(CODE);
        }
        List<Transaction> today = RuleSupport.window(context.customerTransactions(), todayStart, end);
        BigDecimal pastValue = total(context, history);
        BigDecimal todayValue = total(context, today);
        BigDecimal divisor = BigDecimal.valueOf(days);
        boolean countExceeded = BigDecimal.valueOf(today.size()).multiply(divisor)
                .compareTo(BigDecimal.valueOf(history.size()).multiply(multiplier)) > 0;
        boolean valueExceeded = todayValue.multiply(divisor).compareTo(pastValue.multiply(multiplier)) > 0;
        if (!countExceeded && !valueExceeded) {
            return DetectionResult.notTriggered(CODE);
        }
        String exceeded = countExceeded && valueExceeded ? "count and value" : countExceeded ? "count" : "value";
        return DetectionResult.of(CODE, configuration.integer("score", 20), RuleSupport.references(today),
                "Customer daily " + exceeded + " exceeds " + multiplier.toPlainString()
                        + " times the average over the previous " + days + " complete UTC calendar days"
                        + " (including days with no activity), across all customer accounts. Today's count="
                        + today.size() + ", value=" + context.baseCurrency() + " " + todayValue.toPlainString()
                        + "; historical count=" + history.size() + ", value=" + context.baseCurrency() + " "
                        + pastValue.toPlainString() + "; daily averages: count="
                        + BigDecimal.valueOf(history.size()).divide(divisor, 4, RoundingMode.HALF_UP).toPlainString()
                        + ", value=" + pastValue.divide(divisor, 4, RoundingMode.HALF_UP).toPlainString()
                        + ". Today's activity is excluded from the baseline.");
    }

    private BigDecimal total(TransactionContext context, List<Transaction> transactions) {
        return transactions.stream().map(t -> RuleSupport.amount(context, t)).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
