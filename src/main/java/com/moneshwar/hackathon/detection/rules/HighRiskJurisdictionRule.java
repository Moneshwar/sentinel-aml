package com.moneshwar.hackathon.detection.rules;

import com.moneshwar.hackathon.detection.DetectionResult;
import com.moneshwar.hackathon.detection.DetectionRule;
import com.moneshwar.hackathon.detection.RuleConfiguration;
import com.moneshwar.hackathon.detection.TransactionContext;
import com.moneshwar.hackathon.entity.Transaction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class HighRiskJurisdictionRule implements DetectionRule {

    public static final String CODE = "HIGH_RISK_JURISDICTION";

    @Override
    public String getRuleCode() {
        return CODE;
    }

    @Override
    public String getTitle() {
        return "High-Risk Jurisdiction / Sanctioned Counterparty";
    }

    @Override
    public boolean isMandatory() {
        return true;
    }

    @Override
    public DetectionResult evaluate(TransactionContext context, RuleConfiguration configuration) {
        Transaction transaction = context.transaction();
        List<String> matches = new ArrayList<>();
        if (listed(transaction.getCounterpartyCountry(), context.highRiskJurisdictions())) {
            matches.add("counterparty country " + RuleSupport.normalized(transaction.getCounterpartyCountry()));
        }
        if (listed(transaction.getJurisdiction(), context.highRiskJurisdictions())) {
            matches.add("jurisdiction " + RuleSupport.normalized(transaction.getJurisdiction()));
        }
        if (listed(transaction.getCounterpartyAccount(), context.sanctionedCounterparties())) {
            matches.add("counterparty account matched the configured sanctions list");
        }
        if (listed(transaction.getCounterpartyName(), context.sanctionedCounterparties())) {
            matches.add("counterparty name matched the configured sanctions list");
        }
        if (matches.isEmpty()) {
            return DetectionResult.notTriggered(CODE);
        }
        return DetectionResult.of(CODE, configuration.integer("score", 40),
                List.of(RuleSupport.reference(transaction)), "Mandatory review regardless of amount: "
                        + String.join("; ", matches) + ". Matches use the configured high-risk/sanctions lists.");
    }

    private boolean listed(String value, List<String> entries) {
        String normalized = RuleSupport.normalized(value);
        return !normalized.isEmpty() && entries.stream().map(RuleSupport::normalized).anyMatch(normalized::equals);
    }
}
