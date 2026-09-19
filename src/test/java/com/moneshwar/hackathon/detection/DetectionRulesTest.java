package com.moneshwar.hackathon.detection;

import com.moneshwar.hackathon.detection.rules.BehavioralDeviationRule;
import com.moneshwar.hackathon.detection.rules.CtrRule;
import com.moneshwar.hackathon.detection.rules.HighRiskJurisdictionRule;
import com.moneshwar.hackathon.detection.rules.RapidMovementRule;
import com.moneshwar.hackathon.detection.rules.RoundNumberRule;
import com.moneshwar.hackathon.detection.rules.StructuringRule;
import com.moneshwar.hackathon.entity.Account;
import com.moneshwar.hackathon.entity.Customer;
import com.moneshwar.hackathon.entity.Transaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DetectionRulesTest {

    private static final Instant NOW = Instant.parse("2026-09-19T12:00:00Z");
    private static final BigDecimal USD_RATE = new BigDecimal("83.25");
    private final Customer customer = Customer.builder().customerId("CUSTOMER-1").build();
    private final Account account = Account.builder().accountId("ACCOUNT-1").customer(customer).build();

    @Test
    void ctrTriggersAtCurrencyAdjustedBoundaryAndCarriesEvidence() {
        Transaction tx = tx("CTR", "10000", 0, "DEBIT");
        DetectionResult result = new CtrRule().evaluate(context(tx), config("CTR"));
        assertTrue(result.triggered());
        assertEquals(20, result.score());
        assertEquals(List.of("CTR"), result.transactionIds());
        assertTrue(result.explanation().contains("USD 10000"));
        assertTrue(result.explanation().contains("832500"));
    }

    @Test
    void ctrDoesNotTreat10000InrAs10000Usd() {
        Transaction tx = tx("INR", "120", 0, "CREDIT");
        tx.setAmountBase(new BigDecimal("10000"));
        assertFalse(new CtrRule().evaluate(context(tx), config("CTR")).triggered());
        assertFalse(new CtrRule().evaluate(context(tx("LOW", "9999.99", 0, "CREDIT")), config("CTR")).triggered());
    }

    @Test
    void ctrRemainsMandatoryButHonorsConfiguredCurrencyThresholdAndScore() {
        CtrRule rule = new CtrRule();
        Transaction tx = tx("CONFIG", "100", 0, "CREDIT");
        RuleConfiguration configuration = new RuleConfiguration("CTR", false,
                Map.of("currency", "INR", "threshold_amount", "8325", "score", 55));
        assertTrue(rule.isMandatory());
        DetectionResult result = rule.evaluate(context(tx), configuration);
        assertTrue(result.triggered());
        assertEquals(55, result.score());
    }

    @Test
    void ctrConfiguredHigherThresholdCannotBypassMandatoryUsdMaximum() {
        Transaction current = tx("MANDATORY", "10000", 0, "CREDIT");
        DetectionResult result = new CtrRule().evaluate(context(current),
                config("CTR", Map.of("threshold_amount", 20000, "currency", "USD")));
        assertTrue(result.triggered());
        assertTrue(result.explanation().contains("mandatory maximum USD 10000"));
        assertTrue(result.explanation().contains("configured USD 20000"));
    }

    @Test
    void monetaryRulesRejectMissingExchangeRateInsteadOfSilentlyUsingWrongCurrency() {
        Transaction tx = tx("NO-RATE", "10000", 0, "DEBIT");
        TransactionContext context = new TransactionContext(tx, account, customer, List.of(tx), List.of(tx),
                List.of(), List.of(), "INR", Map.of());
        assertThrows(IllegalArgumentException.class, () -> new CtrRule().evaluate(context, config("CTR")));
    }

    @Test
    void ctrCanNormalizeWhenOnlyOriginalAmountIsAvailable() {
        Transaction tx = tx("RAW", "10000", 0, "DEBIT");
        tx.setAmountBase(null);
        assertTrue(new CtrRule().evaluate(context(tx), config("CTR")).triggered());
    }

    @Test
    void structuringIncludesBothAmountEndpointsAndExact24HourBoundary() {
        Transaction a = tx("FIRST", "9000", -24, "CREDIT");
        Transaction b = tx("SECOND", "9999", -2, "CREDIT");
        Transaction current = tx("THIRD", "9500", 0, "CREDIT");
        DetectionResult result = new StructuringRule().evaluate(context(current, a, b, current), config("STRUCTURING"));
        assertTrue(result.triggered());
        assertEquals(List.of("FIRST", "SECOND", "THIRD"), result.transactionIds());
        assertTrue(result.explanation().contains("3 transactions"));
    }

    @Test
    void structuringExcludesOutOfWindowAndFutureTransactions() {
        Transaction old = tx("OLD", "9500", -24, "CREDIT");
        old.setTransactionTime(old.getTransactionTime().minusSeconds(1));
        Transaction future = tx("FUTURE", "9500", 1, "CREDIT");
        Transaction recent = tx("RECENT", "9500", -1, "CREDIT");
        Transaction current = tx("CURRENT", "9500", 0, "CREDIT");
        assertFalse(new StructuringRule().evaluate(context(current, old, future, recent, current), config("STRUCTURING")).triggered());
    }

    @Test
    void structuringExcludesAmountsOutsideRangeAndDuplicateEvidence() {
        Transaction low = tx("LOW", "8999.99", -3, "CREDIT");
        Transaction high = tx("HIGH", "9999.01", -2, "CREDIT");
        Transaction current = tx("CURRENT", "9500", 0, "CREDIT");
        assertFalse(new StructuringRule().evaluate(context(current, low, high, current, current), config("STRUCTURING")).triggered());
    }

    @Test
    void structuringHonorsConfiguredThresholdWindowMinimumAndWeight() {
        Transaction first = tx("FIRST", "100", -30, "CREDIT");
        Transaction current = tx("CURRENT", "110", 0, "CREDIT");
        RuleConfiguration config = config("STRUCTURING", Map.of("threshold_lower", 100, "threshold_upper", 110,
                "window_hours", 36, "min_transactions", 2, "score", 44));
        DetectionResult result = new StructuringRule().evaluate(context(current, first, current), config);
        assertTrue(result.triggered());
        assertEquals(44, result.score());
    }

    @Test
    void structuringDoesNotRealertForUnrelatedCurrentTransaction() {
        Transaction current = tx("UNRELATED", "50", 0, "DEBIT");
        assertFalse(new StructuringRule().evaluate(context(current,
                tx("A", "9500", -3, "CREDIT"), tx("B", "9500", -2, "CREDIT"),
                tx("C", "9500", -1, "CREDIT"), current), config("STRUCTURING")).triggered());
    }

    @Test
    void rapidMovementTriggersAt80PercentAnd48HoursAndExplainsDenominator() {
        Transaction deposit = tx("DEPOSIT", "1000", -48, "CREDIT");
        Transaction first = tx("OUT-1", "300", -1, "DEBIT");
        Transaction current = tx("OUT-2", "500", 0, "DEBIT");
        DetectionResult result = new RapidMovementRule().evaluate(context(current, deposit, first, current), config("RAPID_MOVEMENT"));
        assertTrue(result.triggered());
        assertEquals(List.of("DEPOSIT", "OUT-1", "OUT-2"), result.transactionIds());
        assertTrue(result.explanation().contains("denominator"));
        assertTrue(result.explanation().contains("80.00%"));
    }

    @Test
    void rapidMovementDoesNotCountOutflowsBeforeDeposit() {
        Transaction earlyOut = tx("BEFORE", "900", -3, "DEBIT");
        Transaction deposit = tx("DEPOSIT", "1000", -2, "CREDIT");
        Transaction current = tx("AFTER", "100", 0, "DEBIT");
        assertFalse(new RapidMovementRule().evaluate(context(current, earlyOut, deposit, current), config("RAPID_MOVEMENT")).triggered());
    }

    @Test
    void rapidMovementRejectsBelowThresholdAndExpiredDeposits() {
        Transaction deposit = tx("DEPOSIT", "1000", -48, "CREDIT");
        Transaction current = tx("OUT", "799.99", 0, "DEBIT");
        assertFalse(new RapidMovementRule().evaluate(context(current, deposit, current), config("RAPID_MOVEMENT")).triggered());
        current.setAmountBase(new BigDecimal("800").multiply(USD_RATE));
        deposit.setTransactionTime(deposit.getTransactionTime().minusSeconds(1));
        assertFalse(new RapidMovementRule().evaluate(context(current, deposit, current), config("RAPID_MOVEMENT")).triggered());
    }

    @Test
    void rapidMovementUsesConfiguredWindowFractionAndWeight() {
        Transaction deposit = tx("DEPOSIT", "1000", -60, "CREDIT");
        Transaction current = tx("OUT", "500", 0, "DEBIT");
        DetectionResult result = new RapidMovementRule().evaluate(context(current, deposit, current),
                config("RAPID_MOVEMENT", Map.of("window_hours", 72, "transfer_pct", "0.5", "score", 60)));
        assertTrue(result.triggered());
        assertEquals(60, result.score());
    }

    @Test
    void rapidMovementChecksLaterDepositWithItsOwnDenominator() {
        Transaction earlierDeposit = tx("BIG", "10000", -4, "CREDIT");
        Transaction priorOut = tx("PRIOR-OUT", "100", -3, "DEBIT");
        Transaction laterDeposit = tx("SMALL", "1000", -2, "CREDIT");
        Transaction current = tx("CURRENT", "800", 0, "DEBIT");
        DetectionResult result = new RapidMovementRule().evaluate(context(current,
                earlierDeposit, priorOut, laterDeposit, current), config("RAPID_MOVEMENT"));
        assertTrue(result.triggered());
        assertEquals(List.of("SMALL", "CURRENT"), result.transactionIds());
    }

    @Test
    void rapidMovementDoesNotTreatAmbiguousTransferAsOutflowOrAnInflowAsTrigger() {
        Transaction deposit = tx("DEPOSIT", "1000", -1, "CREDIT");
        Transaction current = tx("AMBIGUOUS", "900", 0, null);
        current.setTransactionType("TRANSFER");
        assertFalse(new RapidMovementRule().evaluate(context(current, deposit, current), config("RAPID_MOVEMENT")).triggered());
        current.setDirection("CREDIT");
        assertFalse(new RapidMovementRule().evaluate(context(current, deposit, current), config("RAPID_MOVEMENT")).triggered());
    }

    @Test
    void highRiskChecksEitherCountryFieldEvenWhenDisabledAndAtTinyAmount() {
        Transaction tx = tx("TINY", "0.01", 0, "DEBIT");
        tx.setCounterpartyCountry(" in ");
        tx.setJurisdiction(" ir ");
        HighRiskJurisdictionRule rule = new HighRiskJurisdictionRule();
        DetectionResult result = rule.evaluate(context(tx, List.of(tx), List.of(tx), List.of("IR"), List.of()),
                new RuleConfiguration(rule.getRuleCode(), false, Map.of("score", 75)));
        assertTrue(rule.isMandatory());
        assertTrue(result.triggered());
        assertEquals(75, result.score());
        assertEquals(List.of("TINY"), result.transactionIds());
        tx.setJurisdiction("IN");
        tx.setCounterpartyCountry("IR");
        assertTrue(rule.evaluate(context(tx, List.of(tx), List.of(tx), List.of("IR"), List.of()), config(rule.getRuleCode())).triggered());
    }

    @Test
    void highRiskChecksConfiguredCounterpartyAccountAndNameWithoutSubstringMatching() {
        Transaction tx = tx("COUNTERPARTY", "1", 0, "DEBIT");
        tx.setCounterpartyAccount(" banned-1 ");
        HighRiskJurisdictionRule rule = new HighRiskJurisdictionRule();
        assertTrue(rule.evaluate(context(tx, List.of(tx), List.of(tx), List.of(), List.of("BANNED-1")), config(rule.getRuleCode())).triggered());
        tx.setCounterpartyAccount("BANNED-10");
        assertFalse(rule.evaluate(context(tx, List.of(tx), List.of(tx), List.of(), List.of("BANNED-1")), config(rule.getRuleCode())).triggered());
        tx.setCounterpartyName(" Listed Trading ");
        assertTrue(rule.evaluate(context(tx, List.of(tx), List.of(tx), List.of(), List.of("listed trading")), config(rule.getRuleCode())).triggered());
    }

    @Test
    void highRiskDoesNotFlagUnlistedCountriesOrEmptyCounterparties() {
        Transaction tx = tx("SAFE", "1", 0, "CREDIT");
        tx.setCounterpartyCountry("IN");
        assertFalse(new HighRiskJurisdictionRule().evaluate(
                context(tx, List.of(tx), List.of(tx), List.of("IR"), List.of("")), config("HIGH_RISK_JURISDICTION")).triggered());
    }

    @Test
    void behavioralValueTriggersAboveThreeTimesButNotExactlyThreeTimes() {
        List<Transaction> history = history(90, "100");
        Transaction current = tx("TODAY", "300", 0, "CREDIT");
        BehavioralDeviationRule rule = new BehavioralDeviationRule();
        assertFalse(rule.evaluate(withHistory(current, history), config(rule.getRuleCode())).triggered());
        current.setAmountBase(new BigDecimal("300.01").multiply(USD_RATE));
        DetectionResult result = rule.evaluate(withHistory(current, history), config(rule.getRuleCode()));
        assertTrue(result.triggered());
        assertTrue(result.explanation().contains("daily value exceeds"));
        assertEquals(List.of("TODAY"), result.transactionIds());
    }

    @Test
    void behavioralCountTriggersIndependentlyOfValueAcrossCustomerAccounts() {
        List<Transaction> history = history(90, "10000");
        Account secondAccount = Account.builder().accountId("ACCOUNT-2").customer(customer).build();
        Transaction a = tx("TODAY-A", "1", -3, "CREDIT");
        a.setAccount(secondAccount);
        Transaction b = tx("TODAY-B", "1", -2, "CREDIT");
        Transaction c = tx("TODAY-C", "1", -1, "CREDIT");
        Transaction current = tx("TODAY-D", "1", 0, "CREDIT");
        List<Transaction> all = new ArrayList<>(history);
        all.addAll(List.of(a, b, c, current));
        DetectionResult result = new BehavioralDeviationRule().evaluate(
                context(current, List.of(b, c, current), all, List.of(), List.of()), config("BEHAVIORAL_DEVIATION"));
        assertTrue(result.triggered());
        assertTrue(result.explanation().contains("daily count exceeds"));
        assertEquals(List.of("TODAY-A", "TODAY-B", "TODAY-C", "TODAY-D"), result.transactionIds());
    }

    @Test
    void behavioralHasNoHistoricalBaselineForNewCustomerOrExpiredOnlyHistory() {
        Transaction current = tx("NEW", "1000000", 0, "CREDIT");
        BehavioralDeviationRule rule = new BehavioralDeviationRule();
        assertFalse(rule.evaluate(context(current), config(rule.getRuleCode())).triggered());
        Transaction expired = tx("EXPIRED", "1", -24 * 91, "CREDIT");
        assertFalse(rule.evaluate(context(current, expired, current), config(rule.getRuleCode())).triggered());
    }

    @Test
    void behavioralUsesFullUtcCalendarDaysIncludingIdleDays() {
        Transaction start = tx("HISTORY-START", "900", 0, "CREDIT");
        start.setTransactionTime(Instant.parse("2026-06-21T00:00:00Z"));
        Transaction current = tx("TODAY", "1", 0, "CREDIT");
        DetectionResult result = new BehavioralDeviationRule().evaluate(context(current, start, current), config("BEHAVIORAL_DEVIATION"));
        assertTrue(result.triggered());
        assertTrue(result.explanation().contains("daily count exceeds"));
        assertTrue(result.explanation().contains("historical count=1"));
        start.setTransactionTime(start.getTransactionTime().minusSeconds(1));
        assertFalse(new BehavioralDeviationRule().evaluate(context(current, start, current), config("BEHAVIORAL_DEVIATION")).triggered());
    }

    @Test
    void behavioralHonorsConfiguredDaysMultiplierAndIgnoresFutureValues() {
        Transaction past = tx("PAST", "100", -24, "CREDIT");
        Transaction current = tx("TODAY", "200", 0, "CREDIT");
        Transaction future = tx("FUTURE", "1000000", 1, "CREDIT");
        RuleConfiguration config = config("BEHAVIORAL_DEVIATION", Map.of("rolling_days", 1, "multiplier", 2, "score", 65));
        BehavioralDeviationRule rule = new BehavioralDeviationRule();
        assertFalse(rule.evaluate(context(current, past, current, future), config).triggered());
        current.setAmountBase(new BigDecimal("200.01").multiply(USD_RATE));
        DetectionResult result = rule.evaluate(context(current, past, current, future), config);
        assertTrue(result.triggered());
        assertEquals(65, result.score());
        assertEquals(List.of("TODAY"), result.transactionIds());
    }

    @Test
    void roundNumberIncludesExactWindowBoundaryAndRequiresRepeatedMultiples() {
        Transaction a = tx("ROUND-A", "1000", -24, "CREDIT");
        Transaction b = tx("ROUND-B", "2000", -1, "CREDIT");
        Transaction current = tx("ROUND-C", "3000", 0, "CREDIT");
        DetectionResult result = new RoundNumberRule().evaluate(context(current, a, b, current), config("ROUND_NUMBER"));
        assertTrue(result.triggered());
        assertEquals(List.of("ROUND-A", "ROUND-B", "ROUND-C"), result.transactionIds());
        a.setTransactionTime(a.getTransactionTime().minusSeconds(1));
        assertFalse(new RoundNumberRule().evaluate(context(current, a, b, current), config("ROUND_NUMBER")).triggered());
    }

    @Test
    void roundNumberRejectsNonmultiplesAndZeroAmounts() {
        Transaction a = tx("ZERO", "0", -2, "CREDIT");
        Transaction b = tx("ALMOST", "1999.99", -1, "CREDIT");
        Transaction current = tx("ROUND", "3000", 0, "CREDIT");
        assertFalse(new RoundNumberRule().evaluate(context(current, a, b, current), config("ROUND_NUMBER")).triggered());
    }

    @Test
    void roundNumberUsesConfiguredCurrencyIntervalWindowMinimumAndWeight() {
        Transaction a = tx("CONFIG-A", "100", -30, "CREDIT");
        Transaction current = tx("CONFIG-B", "200", 0, "CREDIT");
        DetectionResult result = new RoundNumberRule().evaluate(context(current, a, current), config("ROUND_NUMBER",
                Map.of("currency", "INR", "round_interval", "8325", "window_hours", 36,
                        "min_transactions", 2, "score", 25)));
        assertTrue(result.triggered());
        assertEquals(25, result.score());
    }

    @Test
    void optionalRulesCanBeDisabled() {
        Transaction current = tx("DISABLED", "9500", 0, "DEBIT");
        for (DetectionRule rule : List.of(new StructuringRule(), new RapidMovementRule(),
                new BehavioralDeviationRule(), new RoundNumberRule())) {
            assertFalse(rule.isMandatory());
            assertFalse(rule.evaluate(context(current), new RuleConfiguration(rule.getRuleCode(), false, Map.of())).triggered());
        }
    }

    @Test
    void scoresAreBoundedAndDecimalConfigurationPreservesPrecision() {
        BigDecimal precise = new BigDecimal("12345678901234567890.123456789");
        RuleConfiguration configuration = config("CTR", Map.of("precise", precise,
                "integer", new BigInteger("12345678901234567890"), "fraction", 1.5));
        assertEquals(precise, configuration.decimal("precise", BigDecimal.ZERO));
        assertEquals(new BigDecimal("12345678901234567890"), configuration.decimal("integer", BigDecimal.ZERO));
        assertEquals(7, configuration.integer("fraction", 7));
        assertEquals(100, DetectionResult.of("CTR", 200, List.of("T"), "evidence").score());
        assertEquals(0, DetectionResult.of("CTR", -10, List.of("T"), "evidence").score());
    }

    private Transaction tx(String reference, String usdAmount, int hoursFromNow, String direction) {
        BigDecimal amount = new BigDecimal(usdAmount);
        return Transaction.builder().transactionRef(reference).account(account).amount(amount).currency("USD")
                .amountBase(amount.multiply(USD_RATE)).baseCurrency("INR").direction(direction)
                .transactionTime(NOW.plus(hoursFromNow, ChronoUnit.HOURS)).build();
    }

    private RuleConfiguration config(String code) {
        return config(code, Map.of());
    }

    private RuleConfiguration config(String code, Map<String, Object> values) {
        return new RuleConfiguration(code, true, values);
    }

    private TransactionContext context(Transaction current, Transaction... transactions) {
        List<Transaction> records = transactions.length == 0 ? List.of(current) : List.of(transactions);
        return context(current, records, records, List.of(), List.of());
    }

    private TransactionContext context(Transaction current, List<Transaction> accountTransactions,
                                       List<Transaction> customerTransactions, List<String> countries,
                                       List<String> counterparties) {
        return new TransactionContext(current, account, customer, accountTransactions, customerTransactions,
                countries, counterparties, "INR", Map.of("USD", USD_RATE, "INR", BigDecimal.ONE));
    }

    private List<Transaction> history(int days, String usdAmount) {
        List<Transaction> transactions = new ArrayList<>();
        for (int day = 1; day <= days; day++) {
            transactions.add(tx("HISTORY-" + day, usdAmount, -24 * day, "CREDIT"));
        }
        return transactions;
    }

    private TransactionContext withHistory(Transaction current, List<Transaction> history) {
        List<Transaction> transactions = new ArrayList<>(history);
        transactions.add(current);
        return context(current, transactions, transactions, List.of(), List.of());
    }
}
