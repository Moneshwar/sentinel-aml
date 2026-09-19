package com.moneshwar.hackathon.detection;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Immutable, in-memory event-time exchange rates shared by a detection evaluation. */
public final class FxRateTimeline {

    public record Rate(String currency, Instant effectiveFrom, BigDecimal rate) {
    }

    private final String baseCurrency;
    private final Map<String, NavigableMap<Instant, BigDecimal>> history;

    public FxRateTimeline(String baseCurrency, List<Rate> rates) {
        this.baseCurrency = baseCurrency.trim().toUpperCase(Locale.ROOT);
        Map<String, NavigableMap<Instant, BigDecimal>> timeline = new HashMap<>();
        for (Rate rate : rates) {
            if (rate.effectiveFrom() == null || rate.rate() == null || rate.rate().signum() <= 0) {
                throw new IllegalArgumentException("Exchange rates require an effective time and a positive value");
            }
            String currency = rate.currency().trim().toUpperCase(Locale.ROOT);
            timeline.computeIfAbsent(currency, ignored -> new TreeMap<>()).put(rate.effectiveFrom(), rate.rate());
        }
        Map<String, NavigableMap<Instant, BigDecimal>> immutable = new HashMap<>();
        timeline.forEach((currency, values) -> immutable.put(currency, Collections.unmodifiableNavigableMap(values)));
        this.history = Map.copyOf(immutable);
    }

    /** Null means no rate existed yet; a future rate is never applied retroactively. */
    public BigDecimal rateAt(String currency, Instant at) {
        String code = currency.trim().toUpperCase(Locale.ROOT);
        if (code.equals(baseCurrency)) {
            return BigDecimal.ONE;
        }
        NavigableMap<Instant, BigDecimal> values = history.get(code);
        Map.Entry<Instant, BigDecimal> applicable = values == null ? null : values.floorEntry(at);
        return applicable == null ? null : applicable.getValue();
    }

    public Map<String, BigDecimal> ratesAt(Instant at) {
        Map<String, BigDecimal> rates = new HashMap<>();
        rates.put(baseCurrency, BigDecimal.ONE);
        history.keySet().forEach(currency -> {
            BigDecimal rate = rateAt(currency, at);
            if (rate != null) {
                rates.put(currency, rate);
            }
        });
        return Map.copyOf(rates);
    }
}
