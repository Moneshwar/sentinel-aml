package com.moneshwar.hackathon.service.ingestion;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.DuplicateHeaderMode;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.UncheckedIOException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class CsvReader {
    private CsvReader() { }

    public record Row(long recordNumber, Map<String, String> values, String error) {
        public Row(long recordNumber, Map<String, String> values) {
            this(recordNumber, values, null);
        }
    }

    public static List<Row> read(InputStream inputStream) throws IOException {
        List<Row> rows = new ArrayList<>();
        forEach(inputStream, rows::add);
        return rows;
    }

    public static void forEach(InputStream inputStream, Consumer<Row> consumer) throws IOException {
        forEach(inputStream, Set.of(), consumer);
    }

    /** Parse and deliver one record at a time; only the current row is retained. */
    public static void forEach(InputStream inputStream, Set<String> requiredHeaders,
                               Consumer<Row> consumer) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
                .setAllowMissingColumnNames(false)
                .setIgnoreEmptyLines(true)
                .setIgnoreSurroundingSpaces(true)
                .setTrim(true)
                .build();
        try (PushbackReader reader = new PushbackReader(new InputStreamReader(inputStream,
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)), 1)) {
            int first = reader.read();
            if (first != -1 && first != '\uFEFF') reader.unread(first);
            CSVParser parser;
            try {
                parser = CSVParser.parse(reader, format);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid CSV header: " + exception.getMessage(), exception);
            }
            try (parser) {
                List<String> headers = parser.getHeaderNames();
                if (headers.isEmpty()) throw new IOException("CSV must contain a header and data records");
                if (!headers.containsAll(requiredHeaders)) {
                    var missing = new java.util.TreeSet<>(requiredHeaders);
                    missing.removeAll(headers);
                    throw new IOException("Missing required CSV headers: " + String.join(", ", missing));
                }
                boolean hasRecords = false;
                for (CSVRecord record : parser) {
                    hasRecords = true;
                    String error = record.isConsistent() ? null
                            : "CSV row has " + record.size() + " columns; expected " + headers.size();
                    consumer.accept(new Row(record.getRecordNumber(), record.toMap(), error));
                }
                if (!hasRecords) throw new IOException("CSV contains no data records");
            }
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        } catch (IllegalStateException exception) {
            if (exception.getCause() instanceof IOException io) throw io;
            throw exception;
        }
    }
}
