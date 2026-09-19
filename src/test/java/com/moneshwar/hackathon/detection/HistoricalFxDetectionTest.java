package com.moneshwar.hackathon.detection;

import com.moneshwar.hackathon.detection.rules.CtrRule;
import com.moneshwar.hackathon.detection.rules.RoundNumberRule;
import com.moneshwar.hackathon.detection.rules.StructuringRule;
import com.moneshwar.hackathon.entity.Account;
import com.moneshwar.hackathon.entity.Customer;
import com.moneshwar.hackathon.entity.ExchangeRate;
import com.moneshwar.hackathon.entity.Transaction;
import com.moneshwar.hackathon.repository.ExchangeRateRepository;
import com.moneshwar.hackathon.service.ingestion.CurrencyNormalizer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HistoricalFxDetectionTest {

    private static final Instant NOW = Instant.parse("2026-09-19T12:00:00Z");
    private static final Instant RATE_START = NOW.minus(24, ChronoUnit.HOURS);
    private static final Instant RATE_CHANGE = NOW.minus(12, ChronoUnit.HOURS);
    private final Customer customer = Customer.builder().id(1L).customerId("CUSTOMER").build();
    private final Account account = Account.builder().id(1L).accountId("ACCOUNT").customer(customer).build();
    private final FxRateTimeline timeline = new FxRateTimeline("INR", List.of(
            new FxRateTimeline.Rate("USD", RATE_START, new BigDecimal("80")),
            new FxRateTimeline.Rate("USD", RATE_CHANGE, new BigDecimal("100")),
            new FxRateTimeline.Rate("EUR", RATE_START, new BigDecimal("120")),
            new FxRateTimeline.Rate("EUR", RATE_CHANGE, new BigDecimal("125"))));

    @Test
    void structuringIncludesHistoricalUsdAndEurEquivalentsAcrossRateChange() {
        Transaction usd = tx("OLD-USD", "9500", "USD", -20);
        Transaction eur = tx("OLD-EUR", "6400", "EUR", -18); // USD 9,600 at its own timestamp.
        Transaction current = tx("CURRENT", "9700", "USD", 0);
        DetectionResult result = new StructuringRule().evaluate(context(current, usd, eur, current), config("STRUCTURING"));
        assertTrue(result.triggered());
        assertEquals(List.of("OLD-USD", "OLD-EUR", "CURRENT"), result.transactionIds());
    }

    @Test
    void structuringExcludesHistoricalAmountsThatOnlyAppearInRangeAtLaterRate() {
        Transaction usd = tx("OVER-USD", "11250", "USD", -20); // INR 900,000, but USD 11,250 then.
        Transaction eur = tx("OVER-EUR", "7500", "EUR", -18); // Same INR value, also USD 11,250 then.
        Transaction current = tx("CURRENT", "9700", "USD", 0);
        assertFalse(new StructuringRule().evaluate(context(current, usd, eur, current), config("STRUCTURING")).triggered());
    }

    @Test
    void effectiveDateBoundaryUsesNewRateExactlyWhenItTakesEffect() {
        Transaction before = tx("BEFORE", "9500", "USD", -13);
        Transaction exact = tx("EXACT", "9500", "USD", -12);
        Transaction current = tx("CURRENT-EUR", "7600", "EUR", 0); // USD 9,500 at new rates.
        assertEquals(new BigDecimal("100"), timeline.rateAt("USD", RATE_CHANGE));
        assertEquals(new BigDecimal("80"), timeline.rateAt("USD", RATE_CHANGE.minusNanos(1)));
        assertTrue(new StructuringRule().evaluate(context(current, before, exact, current), config("STRUCTURING")).triggered());
    }

    @Test
    void roundNumbersUseEachUsdAndEurRowsOwnReferenceCurrencyRate() {
        Transaction usd = tx("ROUND-USD", "1000", "USD", -20);
        Transaction eur = tx("ROUND-EUR", "2000", "EUR", -18); // USD 3,000 at old rates.
        Transaction current = tx("CURRENT", "2000", "USD", 0);
        DetectionResult result = new RoundNumberRule().evaluate(context(current, usd, eur, current), config("ROUND_NUMBER"));
        assertTrue(result.triggered());
        assertEquals(List.of("ROUND-USD", "ROUND-EUR", "CURRENT"), result.transactionIds());
    }

    @Test
    void roundNumbersExcludeHistoricalValuesThatOnlyBecomeRoundAtLaterRate() {
        Transaction usd = tx("NONROUND-USD", "1250", "USD", -20); // INR 100,000 is not USD 1,000 then.
        Transaction eur = tx("NONROUND-EUR", "2500", "EUR", -18); // USD 3,750 then; INR 300,000.
        Transaction current = tx("CURRENT", "2000", "USD", 0);
        assertFalse(new RoundNumberRule().evaluate(context(current, usd, eur, current), config("ROUND_NUMBER")).triggered());
    }

    @Test
    void ctrUsesTransactionTimeEvenWhenSnapshotMapContainsLaterRate() {
        Transaction old = tx("CTR", "10000", "USD", -20);
        TransactionContext context = new TransactionContext(old, account, customer, List.of(old), List.of(old),
                List.of(), List.of(), "INR", timeline.ratesAt(NOW), timeline);
        assertTrue(new CtrRule().evaluate(context, config("CTR")).triggered());
        old.setAmountBase(new BigDecimal("799999.20"));
        assertFalse(new CtrRule().evaluate(context, config("CTR")).triggered());
    }

    @Test
    void unnormalizedHistoricalAmountAlsoUsesItsOwnSourceCurrencyRate() {
        Transaction usd = tx("OLD-USD", "9500", "USD", -20);
        Transaction eur = tx("RAW-EUR", "6400", "EUR", -18);
        eur.setAmountBase(null);
        Transaction current = tx("CURRENT", "9700", "USD", 0);
        assertTrue(new StructuringRule().evaluate(context(current, usd, eur, current), config("STRUCTURING")).triggered());
    }

    @Test
    void historicalLookupNeverFallsBackToAFutureRate() {
        Transaction old = tx("BEFORE-RATES", "10000", "USD", -20);
        old.setTransactionTime(RATE_START.minusSeconds(1));
        TransactionContext context = new TransactionContext(old, account, customer, List.of(old), List.of(old),
                List.of(), List.of(), "INR", timeline.ratesAt(NOW), timeline);
        assertNull(timeline.rateAt("USD", old.getTransactionTime()));
        assertFalse(timeline.ratesAt(old.getTransactionTime()).containsKey("USD"));
        assertThrows(IllegalArgumentException.class, () -> new CtrRule().evaluate(context, config("CTR")));
    }

    @Test
    void exactMonetaryBoundariesAndRoundMultiplesMatchPersistedCentRounding() {
        FxRateTimeline precise = new FxRateTimeline("INR", List.of(
                new FxRateTimeline.Rate("USD", RATE_START, new BigDecimal("1.23456741"))));
        Transaction ctr = tx("EXACT-CTR", "10000", "USD", 0);
        ctr.setAmountBase(new BigDecimal("12345.67"));
        TransactionContext context = new TransactionContext(ctr, account, customer, List.of(ctr), List.of(ctr),
                List.of(), List.of(), "INR", precise.ratesAt(NOW), precise);
        assertTrue(new CtrRule().evaluate(context, config("CTR")).triggered());

        Transaction a = tx("EXACT-A", "9000", "USD", -2);
        Transaction b = tx("EXACT-B", "9999", "USD", -1);
        Transaction c = tx("EXACT-C", "9500", "USD", 0);
        List<Transaction> values = List.of(a, b, c);
        values.forEach(t -> t.setAmountBase(t.getAmount().multiply(precise.rateAt("USD", t.getTransactionTime()))
                .setScale(2, RoundingMode.HALF_UP)));
        TransactionContext structured = new TransactionContext(c, account, customer, values, values,
                List.of(), List.of(), "INR", precise.ratesAt(NOW), precise);
        assertTrue(new StructuringRule().evaluate(structured, config("STRUCTURING")).triggered());

        a.setAmount(new BigDecimal("1000"));
        b.setAmount(new BigDecimal("2000"));
        c.setAmount(new BigDecimal("3000"));
        values.forEach(t -> t.setAmountBase(t.getAmount().multiply(precise.rateAt("USD", t.getTransactionTime()))
                .setScale(2, RoundingMode.HALF_UP)));
        assertTrue(new RoundNumberRule().evaluate(structured, config("ROUND_NUMBER")).triggered());
    }

    @Test
    void oneRateHistoryQueryServesAllRowsAndRules() {
        ExchangeRateRepository repository = mock(ExchangeRateRepository.class);
        when(repository.findByToCurrencyAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc("INR", NOW))
                .thenReturn(List.of(rate("USD", "100", RATE_CHANGE), rate("EUR", "125", RATE_CHANGE),
                        rate("USD", "80", RATE_START), rate("EUR", "120", RATE_START)));
        CurrencyNormalizer normalizer = new CurrencyNormalizer(repository, "INR");
        FxRateTimeline snapshot = normalizer.rateTimelineThrough(NOW);
        Transaction usd = tx("OLD-USD", "9500", "USD", -20);
        Transaction eur = tx("OLD-EUR", "6400", "EUR", -18);
        Transaction current = tx("CURRENT", "9700", "USD", 0);
        List<Transaction> records = List.of(usd, eur, current);
        TransactionContext context = new TransactionContext(current, account, customer, records, records,
                List.of(), List.of(), "INR", snapshot.ratesAt(NOW), snapshot);
        assertTrue(new StructuringRule().evaluate(context, config("STRUCTURING")).triggered());
        new RoundNumberRule().evaluate(context, config("ROUND_NUMBER"));
        new CtrRule().evaluate(context, config("CTR"));
        verify(repository, times(1)).findByToCurrencyAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc("INR", NOW);
        verifyNoMoreInteractions(repository);
    }

    private ExchangeRate rate(String currency, String value, Instant from) {
        return ExchangeRate.builder().fromCurrency(currency).toCurrency("INR")
                .rate(new BigDecimal(value)).effectiveFrom(from).build();
    }

    private RuleConfiguration config(String code) {
        return new RuleConfiguration(code, true, Map.of());
    }

    private Transaction tx(String reference, String amount, String currency, int hours) {
        Instant at = NOW.plus(hours, ChronoUnit.HOURS);
        BigDecimal value = new BigDecimal(amount);
        return Transaction.builder().transactionRef(reference).account(account).amount(value).currency(currency)
                .amountBase(value.multiply(timeline.rateAt(currency, at)).setScale(2, RoundingMode.HALF_UP))
                .baseCurrency("INR").direction("CREDIT").transactionTime(at).build();
    }

    private TransactionContext context(Transaction current, Transaction... transactions) {
        List<Transaction> records = List.of(transactions);
        return new TransactionContext(current, account, customer, records, records, List.of(), List.of(),
                "INR", timeline.ratesAt(current.getTransactionTime()), timeline);
    }
}
