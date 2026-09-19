package com.moneshwar.hackathon.service;

import com.moneshwar.hackathon.entity.Account;
import com.moneshwar.hackathon.entity.Alert;
import com.moneshwar.hackathon.entity.AuditLog;
import com.moneshwar.hackathon.entity.Case;
import com.moneshwar.hackathon.entity.Customer;
import com.moneshwar.hackathon.entity.ExchangeRate;
import com.moneshwar.hackathon.entity.Transaction;
import com.moneshwar.hackathon.entity.enums.AlertStatus;
import com.moneshwar.hackathon.entity.enums.AuditAction;
import com.moneshwar.hackathon.entity.enums.CaseStatus;
import com.moneshwar.hackathon.repository.AccountRepository;
import com.moneshwar.hackathon.repository.AlertRepository;
import com.moneshwar.hackathon.repository.AuditLogRepository;
import com.moneshwar.hackathon.repository.CaseRepository;
import com.moneshwar.hackathon.repository.CustomerRepository;
import com.moneshwar.hackathon.repository.ExchangeRateRepository;
import com.moneshwar.hackathon.repository.TransactionRepository;
import com.moneshwar.hackathon.service.config.RuleSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** No surrounding test transaction: requests must commit or roll back independently. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:alert-regeneration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.open-in-view=false"
})
@ActiveProfiles("test")
class AlertRegenerationIntegrationTest {
    private static final String ENDPOINT = "/api/v1/admin/alerts/regenerate";
    private static final Instant WHEN = Instant.parse("2025-06-01T10:00:00Z");
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired CustomerRepository customers;
    @Autowired AccountRepository accounts;
    @Autowired TransactionRepository transactions;
    @Autowired AlertRepository alerts;
    @Autowired CaseRepository cases;
    @Autowired AuditLogRepository audit;
    @Autowired ExchangeRateRepository rates;
    @Autowired RuleSettingsService settings;
    MockMvc mvc;
    Customer customer;
    Account account;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        // This class has its own H2 database; production append-only triggers are
        // validated separately against PostgreSQL, not bypassed by this fixture.
        for (String table : List.of("case_alerts", "alert_transactions", "cases", "alerts", "transactions",
                "accounts", "customers", "audit_log", "exchange_rates", "rule_config",
                "high_risk_jurisdictions", "sanctioned_counterparties")) {
            jdbc.update("delete from " + table);
        }
        customer = customers.save(Customer.builder().customerId("REGEN-C").firstName("Synthetic").lastName("Customer").build());
        account = accounts.save(Account.builder().accountId("REGEN-A").customer(customer).currency("INR").build());
        rates.save(ExchangeRate.builder().fromCurrency("USD").toCurrency("INR")
                .rate(new BigDecimal("83.25")).effectiveFrom(Instant.parse("2024-01-01T00:00:00Z")).build());
        for (String rule : List.of("STRUCTURING", "RAPID_MOVEMENT", "BEHAVIORAL_DEVIATION", "ROUND_NUMBER")) {
            settings.update(rule, false, RuleSettingsService.defaults().get(rule));
        }
        ctr("10000", 20);
    }

    @Test
    void previewCountsWithoutChangingAlertsCasesOrAudit() throws Exception {
        Transaction transaction = transaction("REGEN-T", "1000000", WHEN);
        Alert open = alert("REGEN-OPEN", AlertStatus.OPEN, transaction);
        Alert closed = alert("REGEN-CLOSED", AlertStatus.CLOSED, transaction);
        investigation("REGEN-CASE", open, closed);
        var auditBefore = auditRows();
        mvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions").value(1))
                .andExpect(jsonPath("$.alerts").value(2))
                .andExpect(jsonPath("$.caseLinks").value(2));
        assertEquals(2, alerts.count());
        assertEquals(2, count("case_alerts"));
        assertEquals(auditBefore, auditRows());
    }

    @Test
    void onlyAdminCanPreviewOrRegenerateAndConfirmationMustBeExact() throws Exception {
        transaction("REGEN-T", "1000000", WHEN);
        for (String role : List.of("VIEWER", "ANALYST")) {
            mvc.perform(get(ENDPOINT).with(user(role.toLowerCase()).roles(role)))
                    .andExpect(status().isForbidden());
            mvc.perform(post(ENDPOINT).with(user(role.toLowerCase()).roles(role))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"confirmation\":\"REGENERATE\"}"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get(ENDPOINT)).andExpect(status().isUnauthorized());
        mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmation\":\"REGENERATE\"}"))
                .andExpect(status().isUnauthorized());
        for (String body : List.of("{}", "{\"confirmation\":null}", "{\"confirmation\":\"regenerate\"}",
                "{\"confirmation\":\" REGENERATE \"}", "{\"confirmation\":\"DELETE\"}")) {
            mvc.perform(post(ENDPOINT).with(user("admin").roles("ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        }
        mvc.perform(post(ENDPOINT).with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        assertEquals(1, transactions.count());
        assertEquals(0, alerts.count());
        assertEquals(0, audit.count());
    }

    @Test
    void rebuildRemovesOpenClosedAndClearedAlertsButRetainsSourceDataCasesAndExistingAudit() throws Exception {
        Transaction transaction = transaction("REGEN-T", "1000000", WHEN);
        Alert open = alert("REGEN-OPEN", AlertStatus.OPEN, transaction);
        Alert closed = alert("REGEN-CLOSED", AlertStatus.CLOSED, transaction);
        Alert cleared = alert("REGEN-CLEARED", AlertStatus.CLEARED, transaction);
        investigation("REGEN-CASE-1", open, closed);
        investigation("REGEN-CASE-2", closed, cleared);
        var auditBefore = auditRows();
        var sourceBefore = jdbc.queryForList("select id, transaction_ref, amount, currency, amount_base, transaction_time from transactions");
        var casesBefore = jdbc.queryForList("select case_ref, title, status, disposition_reason, opened_at from cases order by case_ref");

        regenerate().andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionsEvaluated").value(1))
                .andExpect(jsonPath("$.alertsDeleted").value(3))
                .andExpect(jsonPath("$.alertsCreated").value(1))
                .andExpect(jsonPath("$.caseLinksRemoved").value(4));

        assertEquals(1, customers.count());
        assertEquals(1, accounts.count());
        assertEquals(1, transactions.count());
        assertEquals(2, cases.count());
        assertEquals(0, count("case_alerts"));
        assertEquals(1, count("alert_transactions"));
        assertEquals(sourceBefore, jdbc.queryForList("select id, transaction_ref, amount, currency, amount_base, transaction_time from transactions"));
        assertEquals(casesBefore, jdbc.queryForList("select case_ref, title, status, disposition_reason, opened_at from cases order by case_ref"));
        for (String reference : List.of("REGEN-OPEN", "REGEN-CLOSED", "REGEN-CLEARED")) {
            assertTrue(alerts.findByAlertRef(reference).isEmpty());
        }
        assertTrue(auditRows().containsAll(auditBefore), "Existing audit rows must remain byte-for-byte equivalent");
        var regenerated = alerts.findAll().getFirst();
        assertEquals(AlertStatus.OPEN, regenerated.getStatus());
        assertEquals("CTR", regenerated.getTriggeredRules());
        assertEquals(20, regenerated.getRiskScore());
        assertNull(regenerated.getDispositionReason());
        assertTrue(audit.findAll().stream().anyMatch(row -> row.getEntityRef().equals("REGEN-CLEARED")
                && "admin".equals(row.getActor()) && row.getDetails() != null && row.getDetails().contains("Original review reason")),
                "Replacing a reviewed alert must retain its disposition in the audit trail");
    }

    @Test
    void currentThresholdEnablementAndScoresReplaceOldFindingsAndRepeatedRebuildDoesNotDuplicate() throws Exception {
        settings.update("ROUND_NUMBER", true, RuleSettingsService.defaults().get("ROUND_NUMBER"));
        transaction("REGEN-6000", "499500", WHEN);
        transaction("REGEN-1000", "83250", WHEN.plusSeconds(60));
        transaction("REGEN-2000", "166500", WHEN.plusSeconds(120));
        transaction("REGEN-3000", "249750", WHEN.plusSeconds(180));
        regenerate().andExpect(status().isOk()).andExpect(jsonPath("$.alertsCreated").value(1));
        assertEquals("ROUND_NUMBER", alerts.findAll().getFirst().getTriggeredRules());
        assertEquals(10, alerts.findAll().getFirst().getRiskScore());

        settings.update("ROUND_NUMBER", false, RuleSettingsService.defaults().get("ROUND_NUMBER"));
        ctr("5000", 67);
        regenerate().andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionsEvaluated").value(4))
                .andExpect(jsonPath("$.alertsDeleted").value(1))
                .andExpect(jsonPath("$.alertsCreated").value(1));
        assertEquals("CTR", alerts.findAll().getFirst().getTriggeredRules());
        assertEquals(67, alerts.findAll().getFirst().getRiskScore());
        assertEquals(1, count("alert_transactions"));

        regenerate().andExpect(status().isOk()).andExpect(jsonPath("$.alertsCreated").value(1));
        assertEquals(1, alerts.count());
        assertEquals(1, count("alert_transactions"));
        assertEquals(67, alerts.findAll().getFirst().getRiskScore());
        assertEquals(4, transactions.count());
    }

    @Test
    void rebuildUsesHistoricalEventTimeRatherThanTodaysExchangeRate() throws Exception {
        transaction("REGEN-HISTORICAL", "840000", WHEN);
        rates.save(ExchangeRate.builder().fromCurrency("USD").toCurrency("INR").rate(new BigDecimal("120"))
                .effectiveFrom(Instant.parse("2026-01-01T00:00:00Z")).build());
        regenerate().andExpect(status().isOk()).andExpect(jsonPath("$.alertsCreated").value(1));
        assertTrue(alerts.findAll().getFirst().getExplanation().contains("832500"));
    }

    @Test
    void missingHistoricalFxDuringReplayRollsBackReplacementLinksAndNewAudit() throws Exception {
        Transaction first = transaction("REGEN-FIRST", "1000000", WHEN);
        transactions.save(Transaction.builder().transactionRef("REGEN-LEGACY-EUR").account(account)
                .amount(new BigDecimal("12000")).currency("EUR").baseCurrency("INR")
                .amountBase(null).transactionTime(WHEN.plusSeconds(60)).build());
        Alert old = alert("REGEN-REVIEWED", AlertStatus.CLEARED, first);
        investigation("REGEN-KEEP-CASE", old);
        var alertsBefore = jdbc.queryForList("select * from alerts order by id");
        var linksBefore = jdbc.queryForList("select * from case_alerts order by case_id, alert_id");
        var evidenceBefore = jdbc.queryForList("select * from alert_transactions order by alert_id, transaction_id");
        var auditBefore = auditRows();
        var casesBefore = jdbc.queryForList("select * from cases order by id");

        regenerate().andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        assertEquals(alertsBefore, jdbc.queryForList("select * from alerts order by id"));
        assertEquals(linksBefore, jdbc.queryForList("select * from case_alerts order by case_id, alert_id"));
        assertEquals(evidenceBefore, jdbc.queryForList("select * from alert_transactions order by alert_id, transaction_id"));
        assertEquals(casesBefore, jdbc.queryForList("select * from cases order by id"));
        assertEquals(auditBefore, auditRows());
        assertEquals(2, transactions.count());
        assertEquals(1, customers.count());
        assertEquals(1, accounts.count());
    }

    @Test
    void allStoredTransactionsAreReplayedAcrossMultipleFetchPages() throws Exception {
        for (int index = 0; index < 130; index++) {
            transaction("REGEN-PAGE-" + index, "1000000", WHEN.plusSeconds((index / 3) * 60L));
        }
        regenerate().andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionsEvaluated").value(130))
                .andExpect(jsonPath("$.alertsCreated").value(1));
        assertEquals(130, transactions.count());
        assertEquals(130, count("alert_transactions"));
        assertEquals(1, alerts.count());
    }

    private org.springframework.test.web.servlet.ResultActions regenerate() throws Exception {
        return mvc.perform(post(ENDPOINT).with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"confirmation\":\"REGENERATE\"}"));
    }

    private void ctr(String threshold, int score) {
        settings.update("CTR", true, Map.of("threshold_amount", new BigDecimal(threshold), "currency", "USD", "score", score));
    }

    private Transaction transaction(String reference, String amount, Instant at) {
        return transactions.save(Transaction.builder().transactionRef(reference).account(account)
                .amount(new BigDecimal(amount)).currency("INR").amountBase(new BigDecimal(amount))
                .baseCurrency("INR").transactionTime(at).build());
    }

    private Alert alert(String reference, AlertStatus status, Transaction transaction) {
        Alert alert = alerts.save(Alert.builder().alertRef(reference).customer(customer).account(account)
                .ruleCode("CTR").triggeredRules("CTR").riskScore(20).status(status)
                .disposition("REVIEWED").dispositionReason("Original review reason")
                .transactions(new LinkedHashSet<>(List.of(transaction))).build());
        audit.save(AuditLog.builder().entityType("ALERT").entityRef(reference).action(AuditAction.STATUS_CHANGED)
                .fromState("OPEN").toState(status.name()).actor("original-analyst")
                .details("Original review reason").occurredAt(Instant.parse("2025-07-01T10:00:00Z")).build());
        return alert;
    }

    private void investigation(String reference, Alert... linked) {
        cases.save(Case.builder().caseRef(reference).customer(customer).title("Retained investigation")
                .status(CaseStatus.INVESTIGATING).dispositionReason("Keep case notes")
                .alerts(new LinkedHashSet<>(List.of(linked))).build());
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    private List<Map<String, Object>> auditRows() {
        return jdbc.queryForList("select * from audit_log order by id");
    }
}
