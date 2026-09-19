package com.moneshwar.hackathon.detection;

import com.moneshwar.hackathon.detection.rules.CtrRule;
import com.moneshwar.hackathon.detection.rules.RapidMovementRule;
import com.moneshwar.hackathon.entity.Account;
import com.moneshwar.hackathon.entity.Alert;
import com.moneshwar.hackathon.entity.Customer;
import com.moneshwar.hackathon.entity.Transaction;
import com.moneshwar.hackathon.entity.enums.AlertStatus;
import com.moneshwar.hackathon.repository.AlertRepository;
import com.moneshwar.hackathon.repository.HighRiskJurisdictionRepository;
import com.moneshwar.hackathon.repository.SanctionedCounterpartyRepository;
import com.moneshwar.hackathon.repository.TransactionRepository;
import com.moneshwar.hackathon.service.AuditLogService;
import com.moneshwar.hackathon.service.config.RuleSettingsService;
import com.moneshwar.hackathon.service.ingestion.CurrencyNormalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DetectionEngineTest {

    private static final Instant NOW = Instant.parse("2026-09-19T12:00:00Z");
    private final TransactionRepository transactions = mock(TransactionRepository.class);
    private final AlertRepository alerts = mock(AlertRepository.class);
    private final RuleSettingsService settings = mock(RuleSettingsService.class);
    private final HighRiskJurisdictionRepository jurisdictions = mock(HighRiskJurisdictionRepository.class);
    private final SanctionedCounterpartyRepository counterparties = mock(SanctionedCounterpartyRepository.class);
    private final CurrencyNormalizer currencies = mock(CurrencyNormalizer.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final Customer customer = Customer.builder().id(1L).customerId("CUST-1").build();
    private final Account account = Account.builder().id(2L).accountId("ACC-1").customer(customer).build();
    private final List<Transaction> stored = new ArrayList<>();

    @BeforeEach
    void configureContext() {
        Map<String, RuleConfiguration> config = new LinkedHashMap<>();
        RuleSettingsService.defaults().forEach((code, values) -> config.put(code, new RuleConfiguration(code, true, values)));
        when(settings.snapshot()).thenReturn(config);
        when(currencies.getBaseCurrency()).thenReturn("USD");
        when(currencies.rateTimelineThrough(any())).thenReturn(new FxRateTimeline("USD", List.of()));
        when(transactions.findByCustomerAndWindow(eq(1L), any(), any())).thenAnswer(invocation -> {
            Instant from = invocation.getArgument(1);
            Instant to = invocation.getArgument(2);
            return stored.stream().filter(t -> !t.getTransactionTime().isBefore(from)
                    && !t.getTransactionTime().isAfter(to)).toList();
        });
    }

    @Test
    void doesNotReplayEveryExistingEqualTimeAnchor() {
        for (int i = 0; i < 50; i++) {
            tx("PREVIOUS-" + i, "10000", 0, "CREDIT");
        }
        Transaction incoming = tx("INCOMING", "10000", 0, "CREDIT");
        CtrRule ctr = spy(new CtrRule());
        engine(ctr).evaluate(incoming);
        verify(ctr, times(1)).evaluate(argThat(context -> context.transaction() == incoming), any());
        verify(alerts, times(1)).save(any());
        verify(transactions, never()).findByTransactionRef(anyString());
    }

    @Test
    void equalTimeDepositReplaysExistingOutflowAndCreatesRapidAlert() {
        Transaction outgoing = tx("A-OUT", "800", 0, "DEBIT");
        Transaction deposit = tx("Z-DEPOSIT", "1000", 0, "CREDIT");
        engine(new RapidMovementRule()).evaluate(deposit);
        ArgumentCaptor<Alert> capture = ArgumentCaptor.forClass(Alert.class);
        verify(alerts).save(capture.capture());
        assertEquals("RAPID_MOVEMENT", capture.getValue().getTriggeredRules());
        assertEquals(Set.of(deposit, outgoing), capture.getValue().getTransactions());
        verify(currencies, times(1)).rateTimelineThrough(NOW);
        verify(jurisdictions, times(1)).findActiveAt(NOW);
    }

    @Test
    void equalTimeReplayDoesNotCrossAccounts() {
        Transaction outgoing = tx("OTHER-OUT", "800", 0, "DEBIT");
        outgoing.setAccount(Account.builder().id(3L).accountId("ACC-2").customer(customer).build());
        Transaction deposit = tx("DEPOSIT", "1000", 0, "CREDIT");
        RapidMovementRule rapid = spy(new RapidMovementRule());
        engine(rapid).evaluate(deposit);
        verify(rapid, times(1)).evaluate(any(), any());
        verify(alerts, never()).save(any());
    }

    @Test
    void lateDepositReevaluatesLaterOutflowUsingOnlyTwoHistoryQueries() {
        Transaction deposit = tx("LATE-DEPOSIT", "1000", 0, "CREDIT");
        Transaction outgoing = tx("LATER-OUT", "800", 1, "DEBIT");
        engine(new RapidMovementRule()).evaluate(deposit);
        ArgumentCaptor<Alert> capture = ArgumentCaptor.forClass(Alert.class);
        verify(alerts).save(capture.capture());
        assertEquals(Set.of(deposit, outgoing), capture.getValue().getTransactions());
        verify(transactions, times(2)).findByCustomerAndWindow(eq(1L), any(), any());
        verify(currencies, times(1)).rateTimelineThrough(outgoing.getTransactionTime());
        verify(currencies, never()).ratesAt(any());
        verify(transactions, never()).findByAccount_IdAndTransactionTimeBetween(anyLong(), any(), any());
    }

    @Test
    void reviewedFindingDoesNotLeakIntoNewerOpenDailyAlert() {
        Transaction reviewed = tx("REVIEWED", "10000", 0, "CREDIT");
        Transaction otherEvidence = tx("OTHER", "10", -1, "CREDIT");
        Alert closed = alert("CLOSED", AlertStatus.CLEARED, "CTR", reviewed);
        Alert open = alert("OPEN", AlertStatus.OPEN, "ROUND_NUMBER", otherEvidence);
        when(alerts.findByDedupKeyStartingWithOrderByCreatedAtDesc(anyString())).thenReturn(List.of(open, closed));
        engine(new CtrRule()).evaluate(reviewed);
        verify(alerts, never()).save(any());
        assertEquals(Set.of(otherEvidence), open.getTransactions());
        assertEquals("ROUND_NUMBER", open.getTriggeredRules());
        verifyNoInteractions(audit);
    }

    @Test
    void newRuleFindingStillCreatesAlertWhileReviewedRuleIsFilteredIndividually() {
        Transaction current = tx("CURRENT", "10000", 0, "CREDIT");
        Alert closed = alert("CLOSED", AlertStatus.CLOSED, "CTR", current);
        when(alerts.findByDedupKeyStartingWithOrderByCreatedAtDesc(anyString())).thenReturn(List.of(closed));
        DetectionRule fresh = fixedRule("ROUND_NUMBER", DetectionResult.of("ROUND_NUMBER", 35,
                List.of("CURRENT"), "New round-number finding"));
        engine(new CtrRule(), fresh).evaluate(current);
        ArgumentCaptor<Alert> capture = ArgumentCaptor.forClass(Alert.class);
        verify(alerts).save(capture.capture());
        assertEquals("ROUND_NUMBER", capture.getValue().getTriggeredRules());
        assertEquals(35, capture.getValue().getRiskScore());
        assertFalse(capture.getValue().getExplanation().contains("CTR:"));
        assertEquals(AlertStatus.CLOSED, closed.getStatus());
    }

    @Test
    void additionalEvidenceForReviewedPatternCreatesNewFinding() {
        Transaction previous = tx("PREVIOUS", "1", -1, "CREDIT");
        Transaction incoming = tx("INCOMING", "1", 0, "CREDIT");
        Alert closed = alert("CLOSED", AlertStatus.CLEARED, "STRUCTURING", previous);
        when(alerts.findByDedupKeyStartingWithOrderByCreatedAtDesc(anyString())).thenReturn(List.of(closed));
        engine(fixedRule("STRUCTURING", DetectionResult.of("STRUCTURING", 30,
                List.of("PREVIOUS", "INCOMING"), "New supporting transaction"))).evaluate(incoming);
        ArgumentCaptor<Alert> capture = ArgumentCaptor.forClass(Alert.class);
        verify(alerts).save(capture.capture());
        assertEquals(Set.of(previous, incoming), capture.getValue().getTransactions());
        assertEquals(Set.of(previous), closed.getTransactions());
    }

    @Test
    void evidenceOutsideLoadedContextIsFetchedInOneBatchAndMissingEvidenceFails() {
        Transaction incoming = tx("CURRENT", "1", 0, "CREDIT");
        Transaction olderA = Transaction.builder().transactionRef("OLDER-A").build();
        Transaction olderB = Transaction.builder().transactionRef("OLDER-B").build();
        when(transactions.findByTransactionRefIn(anyCollection())).thenReturn(List.of(olderA, olderB));
        DetectionRule rule = fixedRule("CTR", DetectionResult.of("CTR", 20,
                List.of("OLDER-A", "OLDER-B"), "Evidence outside current window"));
        engine(rule).evaluate(incoming);
        verify(transactions, times(1)).findByTransactionRefIn(List.of("OLDER-A", "OLDER-B"));
        verify(transactions, never()).findByTransactionRef(anyString());
        when(transactions.findByTransactionRefIn(anyCollection())).thenReturn(List.of(olderA));
        assertThrows(IllegalStateException.class, () -> engine(rule).evaluate(incoming));
    }

    private DetectionEngine engine(DetectionRule... rules) {
        return new DetectionEngine(List.of(rules), settings, transactions, alerts, jurisdictions,
                counterparties, currencies, audit);
    }

    private Transaction tx(String reference, String amount, int hours, String direction) {
        Transaction transaction = Transaction.builder().id((long) stored.size() + 1).transactionRef(reference)
                .account(account).amount(new BigDecimal(amount)).amountBase(new BigDecimal(amount))
                .currency("USD").baseCurrency("USD").direction(direction)
                .transactionTime(NOW.plus(hours, ChronoUnit.HOURS)).build();
        stored.add(transaction);
        return transaction;
    }

    private Alert alert(String reference, AlertStatus status, String codes, Transaction... evidence) {
        return Alert.builder().alertRef(reference).customer(customer).account(account).status(status)
                .ruleCode("COMBINED").triggeredRules(codes).transactions(new LinkedHashSet<>(List.of(evidence))).build();
    }

    private DetectionRule fixedRule(String code, DetectionResult result) {
        return new DetectionRule() {
            @Override
            public String getRuleCode() { return code; }
            @Override
            public String getTitle() { return code; }
            @Override
            public DetectionResult evaluate(TransactionContext context, RuleConfiguration configuration) { return result; }
        };
    }
}
