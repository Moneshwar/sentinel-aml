package com.moneshwar.hackathon.service.ingestion;

import com.moneshwar.hackathon.detection.FxRateTimeline;
import com.moneshwar.hackathon.entity.enums.IngestionErrorType;
import com.moneshwar.hackathon.exception.IngestionRecordException;
import com.moneshwar.hackathon.repository.ExchangeRateRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.math.*;
import java.time.Instant;
import java.util.*;

@Service
public class CurrencyNormalizer {
    private final ExchangeRateRepository repository;
    private final String baseCurrency;
    public CurrencyNormalizer(ExchangeRateRepository repository,@Value("${sentinel.base-currency:INR}") String baseCurrency) {
        this.repository=repository; this.baseCurrency=baseCurrency.toUpperCase(Locale.ROOT);
    }
    public record NormalizedAmount(BigDecimal amountBase,String baseCurrency) {}
    public NormalizedAmount normalize(String currency,BigDecimal amount) { return normalize(currency,amount,Instant.now()); }
    public NormalizedAmount normalize(String currency,BigDecimal amount,Instant at) {
        if(currency==null || amount==null || at==null) throw new IllegalArgumentException("Currency, amount and timestamp are required");
        String source=currency.toUpperCase(Locale.ROOT);
        BigDecimal rate=source.equals(baseCurrency) ? BigDecimal.ONE : repository
            .findTopByFromCurrencyAndToCurrencyAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(source,baseCurrency,at)
            .orElseThrow(() -> new IngestionRecordException(IngestionErrorType.VALIDATION,null,null,"No effective "+source+" to "+baseCurrency+" exchange rate at transaction time")).getRate();
        if(rate.signum()<=0) throw new IllegalArgumentException("Exchange rate must be positive");
        return new NormalizedAmount(amount.multiply(rate).setScale(2,RoundingMode.HALF_UP),baseCurrency);
    }
    public Map<String,BigDecimal> ratesAt(Instant at) {
        return rateTimelineThrough(at).ratesAt(at);
    }
    /** One DB read supplies all historical lookups for every row and replay anchor. */
    public FxRateTimeline rateTimelineThrough(Instant latest) {
        return new FxRateTimeline(baseCurrency, repository
                .findByToCurrencyAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(baseCurrency,latest)
                .stream().map(row -> new FxRateTimeline.Rate(row.getFromCurrency(),row.getEffectiveFrom(),row.getRate()))
                .toList());
    }
    public String getBaseCurrency() { return baseCurrency; }
}
