package com.moneshwar.hackathon.service.ingestion;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;

/**
 * Null-safe, lenient accessor for a single CSV row keyed by header name.
 * Blank cells are treated as absent. Parse failures raise {@link IllegalArgumentException}
 * so the caller can classify the row as {@code MALFORMED}.
 */
public final class CsvValueParser {

    private static final DateTimeFormatter[] DATE_TIME_FORMATS = new DateTimeFormatter[]{
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
    };

    private CsvValueParser() {
    }

    public static String str(Map<String, String> row, String key) {
        String value = row.get(key);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed) ? null : trimmed;
    }

    public static BigDecimal decimal(Map<String, String> row, String key) {
        String value = str(row, key);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.replace(",", ""));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("column '" + key + "' is not a valid decimal: " + value);
        }
    }

    public static Integer integer(Map<String, String> row, String key) {
        String value = str(row, key);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value).intValue();
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("column '" + key + "' is not a valid integer: " + value);
        }
    }

    public static Boolean bool(Map<String, String> row, String key) {
        String value = str(row, key);
        if (value == null) {
            return null;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "y", "yes", "true", "1", "t" -> Boolean.TRUE;
            case "n", "no", "false", "0", "f" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException("column '" + key + "' is not a valid boolean: " + value);
        };
    }

    public static LocalDate date(Map<String, String> row, String key) {
        String value = str(row, key);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("column '" + key + "' is not a valid date: " + value);
        }
    }

    public static Instant timestamp(Map<String, String> row, String key) {
        String value = str(row, key);
        if (value == null) {
            return null;
        }
        return parseInstant(value, key);
    }

    static Instant parseInstant(String value, String key) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        for (DateTimeFormatter format : DATE_TIME_FORMATS) {
            try {
                return LocalDateTime.parse(value, format).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException ignored) {
                // try next
            }
        }
        throw new IllegalArgumentException("column '" + key + "' is not a valid timestamp: " + value);
    }

    public static String currency(Map<String, String> row, String key) {
        String value = str(row, key);
        if (value == null) {
            return null;
        }
        return value.toUpperCase(Locale.ROOT);
    }

    public static <E extends Enum<E>> E enumValue(Map<String, String> row, String key, Class<E> type, E fallback) {
        String value = str(row, key);
        if (value == null) {
            return fallback;
        }
        String normalized = value.toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        try {
            return Enum.valueOf(type, normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("column '" + key + "' has unsupported value '" + value
                    + "'; allowed: " + String.join(",", java.util.Arrays.stream(type.getEnumConstants()).map(Enum::name).toList()));
        }
    }
}
