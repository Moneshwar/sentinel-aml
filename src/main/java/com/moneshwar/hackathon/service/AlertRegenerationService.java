package com.moneshwar.hackathon.service;

import com.moneshwar.hackathon.detection.DetectionEngine;
import com.moneshwar.hackathon.entity.Transaction;
import com.moneshwar.hackathon.entity.enums.AuditAction;
import com.moneshwar.hackathon.repository.AlertRepository;
import com.moneshwar.hackathon.repository.CaseRepository;
import com.moneshwar.hackathon.repository.TransactionRepository;
import com.moneshwar.hackathon.service.config.RuleSettingsService;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.time.Instant;

/** Explicit administrator demo rebuild; never invoked as part of ordinary rule updates. */
@Service
@PreAuthorize("hasRole('ADMIN')")
public class AlertRegenerationService {
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;
    private final AlertRepository alerts;
    private final CaseRepository cases;
    private final TransactionRepository transactions;
    private final DetectionEngine detection;
    private final RuleSettingsService settings;
    private final AuditLogService audit;

    public AlertRegenerationService(EntityManager entityManager, JdbcTemplate jdbc, AlertRepository alerts,
                                    CaseRepository cases, TransactionRepository transactions,
                                    DetectionEngine detection, RuleSettingsService settings, AuditLogService audit) {
        this.entityManager = entityManager;
        this.jdbc = jdbc;
        this.alerts = alerts;
        this.cases = cases;
        this.transactions = transactions;
        this.detection = detection;
        this.settings = settings;
        this.audit = audit;
    }

    public record Preview(long transactions, long alerts, long caseLinks) {}
    public record Result(long transactionsEvaluated, long alertsDeleted, long alertsCreated, long caseLinksRemoved) {}

    @Transactional(readOnly = true)
    public Preview preview() {
        return new Preview(transactions.count(), alerts.count(),
                jdbc.queryForObject("select count(*) from case_alerts", Long.class));
    }

    @Transactional
    public Result regenerate() {
        // Production PostgreSQL writers wait until replacement commits. Ordinary reads
        // remain available and see the previous alerts until then. H2 is used only in tests.
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            if ("PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
                try (var statement = connection.createStatement()) {
                    statement.execute("LOCK TABLE transactions, alerts, cases, case_alerts, alert_transactions, "
                            + "rule_config, exchange_rates, high_risk_jurisdictions, sanctioned_counterparties "
                            + "IN SHARE ROW EXCLUSIVE MODE");
                }
            }
            return null;
        });
        String run = "REBUILD-" + UUID.randomUUID();
        var configuration = settings.snapshot();
        long deleted = alerts.count();
        long links = 0;
        for (var investigation : cases.findAll()) {
            if (investigation.getAlerts().isEmpty()) continue;
            links += investigation.getAlerts().size();
            String refs = investigation.getAlerts().stream().map(a -> a.getAlertRef()).sorted().toList().toString();
            audit.record("CASE", investigation.getCaseRef(), AuditAction.UPDATED,
                    investigation.getStatus().name(), investigation.getStatus().name(), null,
                    run + ": alert regeneration removed links to " + refs);
            investigation.getAlerts().clear();
        }
        // Keep a trace of replaced alerts, including review decisions and evidence.
        for (var alert : alerts.findAll()) {
            audit.record("ALERT", alert.getAlertRef(), AuditAction.UPDATED, alert.getStatus().name(), "REPLACED", null,
                    run + ": replaced using current rules; rules=" + alert.getTriggeredRules()
                            + "; score=" + alert.getRiskScore() + "; assignedTo=" + alert.getAssignedTo()
                            + "; disposition=" + alert.getDisposition() + "; reason=" + alert.getDispositionReason()
                            + "; explanation=" + alert.getExplanation() + "; transactions="
                            + alert.getTransactions().stream().map(Transaction::getTransactionRef).sorted().toList());
        }
        entityManager.flush();
        entityManager.createNativeQuery("delete from alert_transactions").executeUpdate();
        alerts.deleteAllInBatch();
        entityManager.clear();

        // Keyset iteration bounds loaded events and uses original event timestamps.
        long evaluated = 0;
        Long previous = null;
        Instant previousTime = null;
        while (true) {
            var query = entityManager.createQuery("select t from Transaction t join fetch t.account a "
                    + "join fetch a.customer " + (previous == null ? "" :
                    "where t.transactionTime > :time or (t.transactionTime = :time and t.id > :previous) ")
                    + "order by t.transactionTime, t.id", Transaction.class);
            if (previous != null) query.setParameter("time", previousTime).setParameter("previous", previous);
            var batch = query.setMaxResults(100).getResultList();
            if (batch.isEmpty()) break;
            for (Transaction transaction : batch) {
                detection.evaluateStored(transaction, configuration);
                evaluated++;
                previous = transaction.getId();
                previousTime = transaction.getTransactionTime();
            }
            entityManager.flush();
            entityManager.clear();
        }
        long created = alerts.count();
        audit.record("DETECTION", run, AuditAction.UPDATED, null, "COMPLETED", null,
                "Regenerated alerts: transactions=" + evaluated + ", deleted=" + deleted
                        + ", created=" + created + ", caseLinksRemoved=" + links + "; rules=" + configuration);
        return new Result(evaluated, deleted, created, links);
    }
}
