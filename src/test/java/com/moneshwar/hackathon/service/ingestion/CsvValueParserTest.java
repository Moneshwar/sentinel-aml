package com.moneshwar.hackathon.service.ingestion;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CsvValueParserTest {

    @Test
    void blankCellsBecomeNull() {
        Map<String, String> row = Map.of("a", "  ", "b", "null", "c", "");
        assertNull(CsvValueParser.str(row, "a"));
        assertNull(CsvValueParser.str(row, "b"));
        assertNull(CsvValueParser.str(row, "c"));
        assertNull(CsvValueParser.decimal(row, "c"));
    }

    @Test
    void parsesNumbersDatesAndBooleans() {
        Map<String, String> row = Map.of(
                "amount", "10,500.25",
                "when", "2026-09-01T10:15:00Z",
                "date", "2026-09-01",
                "flag", "Y");
        assertEquals(new BigDecimal("10500.25"), CsvValueParser.decimal(row, "amount"));
        assertEquals(Instant.parse("2026-09-01T10:15:00Z"), CsvValueParser.timestamp(row, "when"));
        assertEquals(LocalDate.of(2026, 9, 1), CsvValueParser.date(row, "date"));
        assertEquals(Boolean.TRUE, CsvValueParser.bool(row, "flag"));
    }

    @Test
    void rejectsMalformedValues() {
        Map<String, String> row = Map.of("amount", "abc", "flag", "maybe", "when", "not-a-date");
        assertThrows(IllegalArgumentException.class, () -> CsvValueParser.decimal(row, "amount"));
        assertThrows(IllegalArgumentException.class, () -> CsvValueParser.bool(row, "flag"));
        assertThrows(IllegalArgumentException.class, () -> CsvValueParser.timestamp(row, "when"));
    }

    @Test
    void normalizesEnumValues() {
        Map<String, String> row = Map.of("risk", "high", "status", "in-review");
        assertEquals(com.moneshwar.hackathon.entity.enums.RiskRating.HIGH,
                CsvValueParser.enumValue(row, "risk", com.moneshwar.hackathon.entity.enums.RiskRating.class, null));
        assertEquals(com.moneshwar.hackathon.entity.enums.AlertStatus.IN_REVIEW,
                CsvValueParser.enumValue(row, "status", com.moneshwar.hackathon.entity.enums.AlertStatus.class, null));
    }
}
