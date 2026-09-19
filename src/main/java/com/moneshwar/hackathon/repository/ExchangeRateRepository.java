package com.moneshwar.hackathon.repository;

import com.moneshwar.hackathon.entity.ExchangeRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, Long> {
    Optional<ExchangeRate> findTopByFromCurrencyAndToCurrencyAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(String from, String to, java.time.Instant effectiveAt);
    Optional<ExchangeRate> findByFromCurrencyAndToCurrencyAndEffectiveFrom(String from, String to, java.time.Instant effectiveAt);
    java.util.List<ExchangeRate> findByToCurrencyAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(String to, java.time.Instant effectiveAt);

    Optional<ExchangeRate> findTopByFromCurrencyAndToCurrencyOrderByEffectiveFromDesc(String fromCurrency, String toCurrency);
}
