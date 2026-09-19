package com.moneshwar.hackathon.service.ingestion;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CsvReaderStreamingTest {
    @Test
    void deliversFirstRowBeforeReadingTheWholeFile() {
        var stream = input("customer_id,first_name,last_name\n" + "C,First,Last\n".repeat(100_000));
        class StopReading extends RuntimeException { }
        assertThrows(StopReading.class, () -> CsvReader.forEach(stream, row -> {
            assertTrue(stream.available() > 0, "The first callback must not wait for the entire file");
            assertEquals("C", row.values().get("customer_id"));
            throw new StopReading();
        }));
    }

    @Test
    void acceptsUtf8BomAndQuotedFields() throws Exception {
        var rows = CsvReader.read(input("\uFEFFcustomer_id,first_name,last_name\nC,\"First, second\",Last\n"));
        assertEquals("C", rows.getFirst().values().get("customer_id"));
        assertEquals("First, second", rows.getFirst().values().get("first_name"));
    }

    @Test
    void rejectsEmptyHeaderOnlyDuplicateAndBlankHeaders() {
        for (String malformed : new String[]{"", "\n \n", "a,b\n", "a,a\n1,2\n", "a,,b\n1,2,3\n"}) {
            assertThrows(IOException.class, () -> CsvReader.forEach(input(malformed), row -> fail("Invalid header accepted")));
        }
    }

    @Test
    void rejectsWrongSchemaBeforeProcessingAnyRows() {
        assertThrows(IOException.class, () -> CsvReader.forEach(input("wrong,headers\n1,2\n"),
                Set.of("customer_id"), row -> fail("Missing required header accepted")));
    }

    @Test
    void malformedWidthDoesNotConsumeOrDiscardTheFollowingValidRecord() throws Exception {
        var rows = new ArrayList<CsvReader.Row>();
        CsvReader.forEach(input("a,b\nmissing\nextra,columns,here\ngood,row\n"), rows::add);
        assertEquals(3, rows.size());
        assertNotNull(rows.get(0).error());
        assertNotNull(rows.get(1).error());
        assertNull(rows.get(2).error());
        assertEquals("good", rows.get(2).values().get("a"));
    }

    @Test
    void unrecoverableQuoteFailurePreservesAlreadyDeliveredRows() {
        AtomicInteger delivered = new AtomicInteger();
        assertThrows(IOException.class, () -> CsvReader.forEach(input("a,b\ngood,row\n\"unterminated,row\n"),
                row -> delivered.incrementAndGet()));
        assertEquals(1, delivered.get());
    }

    private ByteArrayInputStream input(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }
}
