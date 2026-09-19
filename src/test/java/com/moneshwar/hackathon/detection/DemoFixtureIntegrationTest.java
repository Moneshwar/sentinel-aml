package com.moneshwar.hackathon.detection;

import com.moneshwar.hackathon.entity.Alert;
import com.moneshwar.hackathon.entity.ExchangeRate;
import com.moneshwar.hackathon.entity.HighRiskJurisdiction;
import com.moneshwar.hackathon.entity.enums.Severity;
import com.moneshwar.hackathon.repository.AlertRepository;
import com.moneshwar.hackathon.repository.ExchangeRateRepository;
import com.moneshwar.hackathon.repository.HighRiskJurisdictionRepository;
import com.moneshwar.hackathon.repository.TransactionRepository;
import com.moneshwar.hackathon.service.ingestion.AccountIngestionService;
import com.moneshwar.hackathon.service.ingestion.CustomerIngestionService;
import com.moneshwar.hackathon.service.ingestion.TransactionIngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:demo-fixtures;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class DemoFixtureIntegrationTest {
    @Autowired CustomerIngestionService customers;
    @Autowired AccountIngestionService accounts;
    @Autowired TransactionIngestionService ingestion;
    @Autowired TransactionRepository transactions;
    @Autowired AlertRepository alerts;
    @Autowired ExchangeRateRepository rates;
    @Autowired HighRiskJurisdictionRepository jurisdictions;
    @Autowired PlatformTransactionManager manager;

    record Expected(int score, Severity severity, int evidence, Set<String> rules) {}

    @Test
    void shippedCsvProducesHighAndCriticalAlertsAndLeavesOrdinaryPurchasesUnflagged() throws Exception {
        rates.save(ExchangeRate.builder().fromCurrency("USD").toCurrency("INR")
                .rate(new BigDecimal("83.25")).effectiveFrom(Instant.EPOCH).build());
        HighRiskJurisdiction country = new HighRiskJurisdiction();
        country.setCountryCode("IR");
        country.setEffectiveFrom(Instant.EPOCH);
        country.setSanctioned(true);
        jurisdictions.save(country);

        Path fixtures = Path.of("docs", "demo-data");
        try (var input = Files.newInputStream(fixtures.resolve("customers.csv"))) {
            var result = customers.importCsv(input, "customers.csv");
            assertEquals(0, result.failed(), result.errors().toString());
            assertEquals(13, result.succeeded());
        }
        try (var input = Files.newInputStream(fixtures.resolve("accounts.csv"))) {
            var result = accounts.importCsv(input, "accounts.csv");
            assertEquals(0, result.failed(), result.errors().toString());
            assertEquals(13, result.succeeded());
        }
        try (var input = Files.newInputStream(fixtures.resolve("transactions.csv"))) {
            var result = ingestion.importCsv(input, "transactions.csv");
            assertEquals(0, result.failed(), result.errors().toString());
            assertEquals(129, result.succeeded());
        }
        assertEquals(129, transactions.count());

        Map<String, Expected> expected = Map.ofEntries(
                Map.entry("HIGH_VALUE_RISK", new Expected(60, Severity.HIGH, 1, Set.of("CTR", "HIGH_RISK_JURISDICTION"))),
                Map.entry("HIGH_STRUCTURING", new Expected(70, Severity.HIGH, 3, Set.of("STRUCTURING", "HIGH_RISK_JURISDICTION"))),
                Map.entry("HIGH_DOMESTIC", new Expected(75, Severity.HIGH, 4, Set.of("CTR", "STRUCTURING", "RAPID_MOVEMENT"))),
                Map.entry("CRITICAL_RAPID", new Expected(85, Severity.CRITICAL, 2, Set.of("CTR", "HIGH_RISK_JURISDICTION", "RAPID_MOVEMENT"))),
                Map.entry("CRITICAL_LAYERING", new Expected(100, Severity.CRITICAL, 4, Set.of("CTR", "HIGH_RISK_JURISDICTION", "STRUCTURING", "RAPID_MOVEMENT", "ROUND_NUMBER"))),
                Map.entry("CRITICAL_FX", new Expected(85, Severity.CRITICAL, 2, Set.of("CTR", "HIGH_RISK_JURISDICTION", "RAPID_MOVEMENT"))),
                Map.entry("LOW_CTR", new Expected(20, Severity.LOW, 1, Set.of("CTR"))));

        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var all = alerts.findAll();
            for (var scenario : expected.entrySet()) {
                String customer = "DEMO-FIXTURE-C-" + scenario.getKey();
                var found = all.stream().filter(a -> customer.equals(a.getCustomer().getCustomerId())).toList();
                assertEquals(1, found.size(), scenario.getKey());
                Alert alert = found.getFirst();
                Expected outcome = scenario.getValue();
                assertEquals(outcome.score(), alert.getRiskScore(), scenario.getKey());
                assertEquals(outcome.severity(), alert.getSeverity(), scenario.getKey());
                assertEquals(outcome.rules(), Set.copyOf(Arrays.asList(alert.getTriggeredRules().split(","))), scenario.getKey());
                assertEquals(outcome.evidence(), alert.getTransactions().size(), scenario.getKey());
                assertTrue(outcome.rules().stream().allMatch(rule -> alert.getExplanation().contains(rule + ":")));
                var expectedRefs = transactions.findAll().stream()
                        .filter(tx -> tx.getTransactionRef().startsWith("DEMO-FIXTURE-T-" + scenario.getKey() + "-"))
                        .map(tx -> tx.getTransactionRef()).collect(Collectors.toSet());
                assertEquals(expectedRefs, alert.getTransactions().stream()
                        .map(tx -> tx.getTransactionRef()).collect(Collectors.toSet()), scenario.getKey());
            }
            assertEquals(3, all.stream().filter(a -> a.getSeverity() == Severity.HIGH).count());
            assertEquals(3, all.stream().filter(a -> a.getSeverity() == Severity.CRITICAL).count());
            assertFalse(all.stream().anyMatch(a -> a.getCustomer().getCustomerId().equals("DEMO-FIXTURE-C-NORMAL")));
        });
    }
}
