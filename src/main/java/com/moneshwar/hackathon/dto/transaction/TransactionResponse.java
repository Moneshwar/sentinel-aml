package com.moneshwar.hackathon.dto.transaction;

import com.moneshwar.hackathon.entity.enums.IngestionSource;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionResponse(
        Long id,
        String transactionRef,
        String accountId,
        String customerId,
        BigDecimal amount,
        String currency,
        BigDecimal amountBase,
        String baseCurrency,
        String transactionType,
        String direction,
        String counterpartyName,
        String counterpartyAccount,
        String counterpartyCountry,
        String channel,
        String jurisdiction,
        String description,
        Instant transactionTime,
        IngestionSource ingestionSource,
        Instant createdAt
) {
}
