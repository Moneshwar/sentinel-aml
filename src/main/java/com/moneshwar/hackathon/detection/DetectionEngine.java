package com.moneshwar.hackathon.detection;

import com.moneshwar.hackathon.entity.Alert;
import com.moneshwar.hackathon.entity.Transaction;
import com.moneshwar.hackathon.entity.enums.AlertStatus;
import com.moneshwar.hackathon.entity.enums.AuditAction;
import com.moneshwar.hackathon.entity.enums.Severity;
import com.moneshwar.hackathon.repository.AlertRepository;
import com.moneshwar.hackathon.repository.HighRiskJurisdictionRepository;
import com.moneshwar.hackathon.repository.SanctionedCounterpartyRepository;
import com.moneshwar.hackathon.repository.TransactionRepository;
import com.moneshwar.hackathon.service.AuditLogService;
import com.moneshwar.hackathon.service.config.RuleSettingsService;
import com.moneshwar.hackathon.service.ingestion.CurrencyNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/** Ingest owns the customer lock. Payment, findings and audit commit or roll back together. */
@Service
public class DetectionEngine {

    private static final Set<AlertStatus> DISPOSED = Set.of(AlertStatus.CLEARED, AlertStatus.CLOSED);
    private final List<DetectionRule> rules;
    private final RuleSettingsService settings;
    private final TransactionRepository transactions;
    private final AlertRepository alerts;
    private final HighRiskJurisdictionRepository jurisdictions;
    private final SanctionedCounterpartyRepository counterparties;
    private final CurrencyNormalizer currencies;
    private final AuditLogService audit;

    public DetectionEngine(List<DetectionRule> rules, RuleSettingsService settings, TransactionRepository transactions,
                           AlertRepository alerts, HighRiskJurisdictionRepository jurisdictions,
                           SanctionedCounterpartyRepository counterparties, CurrencyNormalizer currencies,
                           AuditLogService audit) {
        this.rules = rules;
        this.settings = settings;
        this.transactions = transactions;
        this.alerts = alerts;
        this.jurisdictions = jurisdictions;
        this.counterparties = counterparties;
        this.currencies = currencies;
        this.audit = audit;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void evaluate(Transaction incoming) {
        evaluate(incoming, settings.snapshot(), true);
    }

    /** A full rebuild visits every stored event once, so future anchors need no replay. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void evaluateStored(Transaction incoming, Map<String, RuleConfiguration> config) {
        evaluate(incoming, config, false);
    }

    private void evaluate(Transaction incoming, Map<String, RuleConfiguration> config, boolean replayFuture) {
        List<DetectionRule> active = rules.stream().filter(rule -> config.containsKey(rule.getRuleCode()))
                .filter(rule -> config.get(rule.getRuleCode()).enabled() || rule.isMandatory()).toList();
        if (active.isEmpty()) {
            return;
        }
        int days = active.stream().map(rule -> config.get(rule.getRuleCode()))
                .mapToInt(c -> c.integer("rolling_days", 0)).max().orElse(0);
        int hours = active.stream().map(rule -> config.get(rule.getRuleCode()))
                .mapToInt(c -> c.integer("window_hours", 0)).max().orElse(0);
        long customerId = incoming.getAccount().getCustomer().getId();
        Instant incomingTime = incoming.getTransactionTime();

        // The incoming event sees all equal-time records already persisted. Most earlier
        // equal-time anchors therefore add no information; rules can request a replay
        // when direction matters (a late deposit can affect an existing debit).
        List<Transaction> anchors = new ArrayList<>();
        anchors.add(incoming);
        if (replayFuture) transactions.findByCustomerAndWindow(customerId, incomingTime,
                        incomingTime.plus(Duration.ofDays(Math.max(days, (hours + 23) / 24) + 1)))
                .stream().filter(t -> !t.getTransactionRef().equals(incoming.getTransactionRef()))
                .filter(t -> t.getTransactionTime().isAfter(incomingTime)
                        || active.stream().anyMatch(rule -> rule.requiresSameTimestampReplay(incoming, t)))
                .forEach(anchors::add);
        anchors.sort(Comparator.comparing(Transaction::getTransactionTime).thenComparing(Transaction::getTransactionRef));

        Instant firstHistory = midnightMinusDays(incomingTime, days);
        Instant firstAccount = incomingTime.minus(Duration.ofHours(hours));
        Instant first = firstHistory.isBefore(firstAccount) ? firstHistory : firstAccount;
        Instant last = anchors.get(anchors.size() - 1).getTransactionTime();
        // Read the affected customer history once and derive bounded contexts in memory.
        Map<String, Transaction> loaded = new LinkedHashMap<>();
        transactions.findByCustomerAndWindow(customerId, first, last)
                .forEach(t -> loaded.put(t.getTransactionRef(), t));
        loaded.put(incoming.getTransactionRef(), incoming);
        List<Transaction> history = new ArrayList<>(loaded.values());
        List<String> sanctioned = counterparties.findAllByEnabledTrue().stream().map(c -> c.getIdentifier()).toList();
        Map<Instant, List<String>> jurisdictionCache = new HashMap<>();
        FxRateTimeline fxRates = currencies.rateTimelineThrough(last);
        for (Transaction anchor : anchors) {
            Instant now = anchor.getTransactionTime();
            Instant historyStart = midnightMinusDays(now, days);
            Instant accountStart = now.minus(Duration.ofHours(hours));
            List<Transaction> customerHistory = history.stream()
                    .filter(t -> between(t, historyStart, now)).toList();
            List<Transaction> accountHistory = history.stream()
                    .filter(t -> Objects.equals(t.getAccount().getId(), anchor.getAccount().getId()))
                    .filter(t -> between(t, accountStart, now)).toList();
            TransactionContext context = new TransactionContext(anchor, anchor.getAccount(), anchor.getAccount().getCustomer(),
                    accountHistory, customerHistory, jurisdictionCache.computeIfAbsent(now,
                    at -> jurisdictions.findActiveAt(at).stream().map(j -> j.getCountryCode()).toList()),
                    sanctioned, currencies.getBaseCurrency(), fxRates.ratesAt(now), fxRates);
            List<DetectionResult> results = active.stream()
                    .map(rule -> rule.evaluate(context, config.get(rule.getRuleCode())))
                    .filter(DetectionResult::triggered).toList();
            if (!results.isEmpty()) {
                persist(context, results, config);
            }
        }
    }

    private Instant midnightMinusDays(Instant at, int days) {
        return at.atZone(ZoneOffset.UTC).toLocalDate().minusDays(days).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private boolean between(Transaction transaction, Instant start, Instant end) {
        return !transaction.getTransactionTime().isBefore(start) && !transaction.getTransactionTime().isAfter(end);
    }

    private void persist(TransactionContext context, List<DetectionResult> results, Map<String, RuleConfiguration> config) {
        String group = "CUSTOMER:" + context.customer().getId() + ":"
                + context.transaction().getTransactionTime().atZone(ZoneOffset.UTC).toLocalDate() + ":";
        List<Alert> previous = alerts.findByDedupKeyStartingWithOrderByCreatedAtDesc(group);
        List<DisposedFinding> disposed = previous.stream().filter(a -> DISPOSED.contains(a.getStatus()))
                .map(a -> new DisposedFinding(codesOf(a), a.getTransactions().stream()
                        .map(Transaction::getTransactionRef).collect(Collectors.toSet()))).toList();
        // Filter reviewed findings even while a newer alert is open. A pattern with
        // additional evidence remains a new finding and keeps its supporting evidence.
        results = results.stream().filter(result -> disposed.stream().noneMatch(closed ->
                closed.codes().contains(result.ruleCode()) && closed.evidence().containsAll(result.transactionIds()))).toList();
        if (results.isEmpty()) {
            return;
        }
        Alert alert = previous.stream().filter(a -> !DISPOSED.contains(a.getStatus())).findFirst().orElse(null);
        Set<String> evidence = results.stream().flatMap(r -> r.transactionIds().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> codes = results.stream().map(DetectionResult::ruleCode).collect(Collectors.toCollection(TreeSet::new));
        boolean created = alert == null;
        if (created) {
            alert = new Alert();
            alert.setAlertRef("ALT-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT));
            alert.setCustomer(context.customer());
            alert.setAccount(context.account());
            alert.setDedupKey(group + UUID.randomUUID());
            alert.setRuleCode("COMBINED");
            alert.setTitle("Suspicious customer activity · "
                    + context.transaction().getTransactionTime().atZone(ZoneOffset.UTC).toLocalDate());
        }
        int oldCount = alert.getTransactions().size();
        Set<String> oldCodes = codesOf(alert);
        codes.addAll(oldCodes);
        List<String> explanations = new ArrayList<>();
        if (alert.getExplanation() != null) {
            explanations.addAll(Arrays.asList(alert.getExplanation().split("\n")));
        }
        for (DetectionResult result : results) {
            explanations.removeIf(s -> s.startsWith(result.ruleCode() + ": "));
            explanations.add(result.ruleCode() + ": " + result.explanation());
        }
        Map<String, Transaction> evidenceEntities = new HashMap<>();
        context.customerTransactions().forEach(t -> evidenceEntities.put(t.getTransactionRef(), t));
        context.accountTransactions().forEach(t -> evidenceEntities.put(t.getTransactionRef(), t));
        evidenceEntities.put(context.transaction().getTransactionRef(), context.transaction());
        List<String> missing = evidence.stream().filter(ref -> !evidenceEntities.containsKey(ref)).toList();
        if (!missing.isEmpty()) {
            transactions.findByTransactionRefIn(missing).forEach(t -> evidenceEntities.put(t.getTransactionRef(), t));
        }
        for (String reference : evidence) {
            Transaction transaction = evidenceEntities.get(reference);
            if (transaction == null) {
                throw new IllegalStateException("Detection evidence transaction does not exist: " + reference);
            }
            alert.getTransactions().add(transaction);
        }
        Map<String, Integer> weights = new HashMap<>();
        codes.forEach(code -> weights.put(code, config.containsKey(code)
                ? Math.max(0, Math.min(100, config.get(code).integer("score", 0))) : 0));
        results.forEach(result -> weights.put(result.ruleCode(), result.score()));
        int score = weights.values().stream().mapToInt(Integer::intValue).sum();
        alert.setRiskScore(Math.max(alert.getRiskScore(), Math.min(100, score)));
        alert.setSeverity(alert.getRiskScore() >= 80 ? Severity.CRITICAL : alert.getRiskScore() >= 60
                ? Severity.HIGH : alert.getRiskScore() >= 30 ? Severity.MEDIUM : Severity.LOW);
        alert.setTriggeredRules(String.join(",", codes));
        alert.setExplanation(String.join("\n", explanations));
        alerts.save(alert);
        if (created || oldCount != alert.getTransactions().size() || !oldCodes.equals(codes)) {
            audit.record("ALERT", alert.getAlertRef(), created ? AuditAction.CREATED : AuditAction.UPDATED,
                    created ? null : alert.getStatus().name(), alert.getStatus().name(), "SYSTEM",
                    "Detection: " + String.join(", ", codes));
        }
    }

    private Set<String> codesOf(Alert alert) {
        return alert.getTriggeredRules() == null || alert.getTriggeredRules().isBlank() ? new TreeSet<>()
                : new TreeSet<>(Arrays.asList(alert.getTriggeredRules().split(",")));
    }

    private record DisposedFinding(Set<String> codes, Set<String> evidence) {
    }
}
