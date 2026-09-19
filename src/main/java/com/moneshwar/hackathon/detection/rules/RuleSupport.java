package com.moneshwar.hackathon.detection.rules;

import com.moneshwar.hackathon.detection.RuleConfiguration;
import com.moneshwar.hackathon.detection.TransactionContext;
import com.moneshwar.hackathon.entity.Transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class RuleSupport {

    private static final Set<String> INFLOWS = Set.of("CREDIT", "IN", "INBOUND", "INCOMING", "DEPOSIT");
    private static final Set<String> OUTFLOWS = Set.of("DEBIT", "OUT", "OUTBOUND", "OUTGOING", "WITHDRAWAL");

    private RuleSupport() {
    }

    static String normalized(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    static BigDecimal rate(TransactionContext context, String currency) {
        return rate(context, currency, context.transaction());
    }

    static BigDecimal rate(TransactionContext context, String currency, Transaction transaction) {
        String code = normalized(currency);
        if (code.equals(normalized(context.baseCurrency()))) {
            return BigDecimal.ONE;
        }
        BigDecimal rate = context.fxRateTimeline() == null ? context.ratesToBase().get(code)
                : context.fxRateTimeline().rateAt(code, transaction.getTransactionTime());
        if (rate == null || rate.signum() <= 0) {
            throw new IllegalArgumentException("No positive " + code + " to " + context.baseCurrency()
                    + " exchange rate for detection at " + transaction.getTransactionTime());
        }
        return rate;
    }

    static BigDecimal amount(TransactionContext context, Transaction transaction) {
        if (transaction.getAmountBase() != null) {
            if (transaction.getBaseCurrency() != null
                    && !normalized(transaction.getBaseCurrency()).equals(normalized(context.baseCurrency()))) {
                throw new IllegalArgumentException("Transaction base currency differs from detection base currency");
            }
            return transaction.getAmountBase();
        }
        if (transaction.getAmount() == null) {
            throw new IllegalArgumentException("Transaction amount is required for detection");
        }
        return normalizedAmount(transaction.getAmount(), rate(context, transaction.getCurrency(), transaction));
    }

    /** Match the two-decimal base amounts persisted by CurrencyNormalizer. */
    static BigDecimal normalizedAmount(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    static String currency(RuleConfiguration configuration) {
        return normalized(configuration.string("currency", "USD"));
    }

    static BigDecimal positive(RuleConfiguration configuration, String key, String fallback) {
        BigDecimal value = configuration.decimal(key, new BigDecimal(fallback));
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(key + " must be positive");
        }
        return value;
    }

    static int positiveInteger(RuleConfiguration configuration, String key, int fallback) {
        int value = configuration.integer(key, fallback);
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be positive");
        }
        return value;
    }

    static List<Transaction> accountWindow(TransactionContext context, RuleConfiguration configuration,
                                           int defaultHours) {
        Instant end = context.transaction().getTransactionTime();
        Instant start = end.minus(positiveInteger(configuration, "window_hours", defaultHours), ChronoUnit.HOURS);
        return window(context.accountTransactions(), start, end);
    }

    static List<Transaction> window(List<Transaction> transactions, Instant start, Instant end) {
        LinkedHashMap<String, Transaction> unique = new LinkedHashMap<>();
        transactions.stream().filter(t -> t.getTransactionTime() != null)
                .filter(t -> !t.getTransactionTime().isBefore(start) && !t.getTransactionTime().isAfter(end))
                .sorted(Comparator.comparing(Transaction::getTransactionTime).thenComparing(RuleSupport::reference))
                .forEach(t -> unique.putIfAbsent(reference(t), t));
        return List.copyOf(unique.values());
    }

    static String reference(Transaction transaction) {
        return transaction.getTransactionRef() != null ? transaction.getTransactionRef()
                : String.valueOf(transaction.getId());
    }

    static List<String> references(List<Transaction> transactions) {
        return transactions.stream().map(RuleSupport::reference).distinct().toList();
    }

    static boolean inflow(Transaction transaction) {
        String direction = normalized(transaction.getDirection());
        if (!direction.isEmpty()) {
            return INFLOWS.contains(direction);
        }
        return Set.of("DEPOSIT", "CASH_DEPOSIT").contains(normalized(transaction.getTransactionType()));
    }

    static boolean outflow(Transaction transaction) {
        String direction = normalized(transaction.getDirection());
        if (!direction.isEmpty()) {
            return OUTFLOWS.contains(direction);
        }
        return Set.of("WITHDRAWAL", "CASH_WITHDRAWAL", "TRANSFER_OUT")
                .contains(normalized(transaction.getTransactionType()));
    }
}
