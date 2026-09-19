package com.moneshwar.hackathon.detection;

import com.moneshwar.hackathon.entity.Account;
import com.moneshwar.hackathon.entity.Customer;
import com.moneshwar.hackathon.entity.Transaction;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything a rule needs to evaluate one transaction. Assembled once by the
 * DetectionEngine so rules stay pure and unit-testable — they never touch the
 * database directly.
 */
public record TransactionContext(
        Transaction transaction,
        Account account,
        Customer customer,
        List<Transaction> accountTransactions,
        List<Transaction> customerTransactions,
        List<String> highRiskJurisdictions,
        List<String> sanctionedCounterparties,
        String baseCurrency,
        Map<String, BigDecimal> ratesToBase,
        FxRateTimeline fxRateTimeline
) {
    /** Compatibility for a context whose exchange rates remain fixed throughout its window. */
    public TransactionContext(Transaction transaction, Account account, Customer customer,
                              List<Transaction> accountTransactions, List<Transaction> customerTransactions,
                              List<String> highRiskJurisdictions, List<String> sanctionedCounterparties,
                              String baseCurrency, Map<String, BigDecimal> ratesToBase) {
        this(transaction, account, customer, accountTransactions, customerTransactions, highRiskJurisdictions,
                sanctionedCounterparties, baseCurrency, ratesToBase, null);
    }

    public TransactionContext {
        Objects.requireNonNull(transaction, "transaction");
        accountTransactions = List.copyOf(accountTransactions);
        customerTransactions = List.copyOf(customerTransactions);
        highRiskJurisdictions = List.copyOf(highRiskJurisdictions);
        sanctionedCounterparties = List.copyOf(sanctionedCounterparties);
        ratesToBase = Map.copyOf(ratesToBase);
    }
}
