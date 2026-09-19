package com.moneshwar.hackathon.detection.rules;

import com.moneshwar.hackathon.detection.DetectionResult;
import com.moneshwar.hackathon.detection.DetectionRule;
import com.moneshwar.hackathon.detection.RuleConfiguration;
import com.moneshwar.hackathon.detection.TransactionContext;
import com.moneshwar.hackathon.entity.Transaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
public class RapidMovementRule implements DetectionRule {

    public static final String CODE = "RAPID_MOVEMENT";

    @Override
    public String getRuleCode() {
        return CODE;
    }

    @Override
    public String getTitle() {
        return "Rapid Movement of Funds";
    }

    @Override
    public boolean requiresSameTimestampReplay(Transaction incoming, Transaction previous) {
        return RuleSupport.inflow(incoming) && RuleSupport.outflow(previous)
                && incoming.getAccount() != null && previous.getAccount() != null
                && Objects.equals(incoming.getAccount().getAccountId(), previous.getAccount().getAccountId());
    }

    @Override
    public DetectionResult evaluate(TransactionContext context, RuleConfiguration configuration) {
        if (!configuration.enabled() || !RuleSupport.outflow(context.transaction())) {
            return DetectionResult.notTriggered(CODE);
        }
        BigDecimal fraction = RuleSupport.positive(configuration, "transfer_pct", "0.80");
        if (fraction.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("transfer_pct must be a fraction greater than 0 and at most 1");
        }
        List<Transaction> window = RuleSupport.accountWindow(context, configuration, 48);
        List<Transaction> outgoing = window.stream().filter(RuleSupport::outflow)
                .filter(t -> RuleSupport.amount(context, t).signum() > 0).toList();
        BigDecimal transferred = outgoing.stream().map(t -> RuleSupport.amount(context, t))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int firstOutgoing = 0;
        for (Transaction deposit : window) {
            if (!RuleSupport.inflow(deposit)) {
                continue;
            }
            BigDecimal depositAmount = RuleSupport.amount(context, deposit);
            if (depositAmount.signum() <= 0) {
                continue;
            }
            while (firstOutgoing < outgoing.size()
                    && outgoing.get(firstOutgoing).getTransactionTime().isBefore(deposit.getTransactionTime())) {
                transferred = transferred.subtract(RuleSupport.amount(context, outgoing.get(firstOutgoing)));
                firstOutgoing++;
            }
            if (transferred.compareTo(depositAmount.multiply(fraction)) >= 0) {
                List<Transaction> evidence = new ArrayList<>();
                evidence.add(deposit);
                evidence.addAll(outgoing.subList(firstOutgoing, outgoing.size()));
                BigDecimal actualPercent = transferred.multiply(new BigDecimal("100"))
                        .divide(depositAmount, 2, RoundingMode.HALF_UP);
                return DetectionResult.of(CODE, configuration.integer("score", 25),
                        RuleSupport.references(evidence), "Deposit " + RuleSupport.reference(deposit)
                                + " of " + context.baseCurrency() + " " + depositAmount.toPlainString()
                                + " is the denominator; subsequent outgoing transactions total "
                                + context.baseCurrency() + " " + transferred.toPlainString() + " ("
                                + actualPercent.toPlainString() + "% of that deposit), meeting the "
                                + fraction.multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString()
                                + "% threshold within " + configuration.integer("window_hours", 48)
                                + " hours. Outflows before the deposit are excluded; this temporal pattern"
                                + " does not establish that specific funds were traced.");
            }
        }
        return DetectionResult.notTriggered(CODE);
    }
}
