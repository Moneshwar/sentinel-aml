package com.moneshwar.hackathon.stream;

import com.moneshwar.hackathon.entity.enums.IngestionSource;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Emitted after a transaction is durably persisted. The detection engine
 * (Part B) subscribes to these events to score transactions in near real-time.
 */
public record TransactionIngestedEvent(
        Long transactionId,
        String transactionRef,
        String accountId,
        String customerId,
        BigDecimal amount,
        String currency,
        BigDecimal amountBase,
        String baseCurrency,
        Instant transactionTime,
        IngestionSource source
) {
}
