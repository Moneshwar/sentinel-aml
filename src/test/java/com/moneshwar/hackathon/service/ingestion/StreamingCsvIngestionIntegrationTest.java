package com.moneshwar.hackathon.service.ingestion;

import com.moneshwar.hackathon.dto.customer.CustomerRequest;
import com.moneshwar.hackathon.dto.ingestion.IngestionResult;
import com.moneshwar.hackathon.entity.Account;
import com.moneshwar.hackathon.entity.Customer;
import com.moneshwar.hackathon.entity.ExchangeRate;
import com.moneshwar.hackathon.entity.enums.IngestionEntityType;
import com.moneshwar.hackathon.entity.enums.IngestionErrorType;
import com.moneshwar.hackathon.repository.AccountRepository;
import com.moneshwar.hackathon.repository.CustomerRepository;
import com.moneshwar.hackathon.repository.ExchangeRateRepository;
import com.moneshwar.hackathon.repository.IngestionErrorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StreamingCsvIngestionIntegrationTest {
    @Autowired CustomerIngestionService customers;
    @Autowired AccountIngestionService accounts;
    @Autowired TransactionIngestionService transactions;
    @Autowired CustomerRepository customerRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired ExchangeRateRepository rates;
    @Autowired IngestionErrorRepository errors;

    @ParameterizedTest
    @EnumSource(IngestionEntityType.class)
    void countsAllRejectedRowsWhileRetainingOnly200ResponseErrors(IngestionEntityType type) {
        var customer = customerRepository.save(Customer.builder().customerId("CSV-SEED-C")
                .firstName("Synthetic").lastName("Customer").build());
        accountRepository.save(Account.builder().accountId("CSV-SEED-A").customer(customer).currency("INR").build());
        rates.save(ExchangeRate.builder().fromCurrency("USD").toCurrency("INR")
                .rate(new BigDecimal("83.25")).effectiveFrom(Instant.EPOCH).build());
        String header;
        String bad;
        String good;
        switch (type) {
            case CUSTOMER -> {
                header = "customer_id,first_name,last_name,annual_income\n";
                bad = "CSV-BAD-C,Test,Person,not-a-number\n";
                good = "CSV-GOOD-C,Test,Person,100\n";
            }
            case ACCOUNT -> {
                header = "account_id,customer_id,currency,current_balance\n";
                bad = "CSV-BAD-A,CSV-SEED-C,INR,not-a-number\n";
                good = "CSV-GOOD-A,CSV-SEED-C,INR,100\n";
            }
            case TRANSACTION -> {
                header = "transaction_ref,account_id,amount,currency,transaction_time\n";
                bad = "CSV-BAD-T,CSV-SEED-A,not-a-number,INR,2026-09-01T00:00:00Z\n";
                good = "CSV-GOOD-T,CSV-SEED-A,100,INR,2026-09-01T00:00:00Z\n";
            }
            default -> throw new IllegalArgumentException();
        }
        var result = ingest(type, header + bad.repeat(205) + good);
        assertEquals(206, result.totalRecords());
        assertEquals(205, result.failed());
        assertEquals(1, result.succeeded());
        assertEquals(200, result.errors().size());
        assertEquals(205, errors.countByBatchId(result.batchId()));
        assertEquals(IngestionErrorType.MALFORMED, result.errors().getFirst().errorType());
    }

    @ParameterizedTest
    @EnumSource(IngestionEntityType.class)
    void rejectsEmptyAndInvalidHeaderFiles(IngestionEntityType type) {
        for (String invalid : new String[]{"", "unrelated,headers\na,b\n", "duplicate,duplicate\na,b\n"}) {
            var result = ingest(type, invalid);
            assertEquals(0, result.succeeded());
            assertEquals(1, result.failed());
            assertEquals(IngestionErrorType.MALFORMED, result.errors().getFirst().errorType());
        }
    }

    @Test
    void aBadWidthRowDoesNotStopTheNextValidCustomer() {
        var result = customers.importCsv(input("""
                customer_id,first_name,last_name
                CSV-BAD-WIDTH,Missing
                CSV-GOOD-WIDTH,Synthetic,Person
                """), "customers.csv");
        assertEquals(2, result.totalRecords());
        assertEquals(1, result.failed());
        assertEquals(1, result.succeeded());
        assertTrue(customerRepository.findByCustomerId("CSV-GOOD-WIDTH").isPresent());
    }

    @Test
    void malformedTrailingQuoteReportsFailureAndKeepsEarlierAcceptedRecord() {
        var result = customers.importCsv(input("customer_id,first_name,last_name\nCSV-BEFORE,Good,Person\n\"broken"), "customers.csv");
        assertEquals(1, result.succeeded());
        assertEquals(1, result.failed());
        assertEquals(2, result.totalRecords());
        assertTrue(customerRepository.findByCustomerId("CSV-BEFORE").isPresent());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedCustomerUpdateRollsBackBeforeTheNextRowCommits() {
        CustomerRequest original = new CustomerRequest();
        original.setCustomerId("CSV-ATOMIC-ORIGINAL");
        original.setFirstName("Original");
        original.setLastName("Person");
        original.setAnnualIncome(new BigDecimal("100"));
        customers.upsert(original);
        IngestionResult result = null;
        try {
            result = customers.importCsv(input("""
                    customer_id,first_name,last_name,annual_income
                    CSV-ATOMIC-ORIGINAL,ShouldRollback,Person,99999999999999999999999999999
                    CSV-ATOMIC-NEXT,Accepted,Person,200
                    """), "customers.csv");
            assertEquals(1, result.failed());
            assertEquals(1, result.succeeded());
            assertEquals(IngestionErrorType.PERSISTENCE, result.errors().getFirst().errorType());
            assertEquals("Original", customerRepository.findByCustomerId("CSV-ATOMIC-ORIGINAL").orElseThrow().getFirstName());
            assertEquals("Accepted", customerRepository.findByCustomerId("CSV-ATOMIC-NEXT").orElseThrow().getFirstName());
        } finally {
            if (result != null) errors.deleteAll(errors.findByBatchId(result.batchId(), Pageable.unpaged()));
            customerRepository.findByCustomerId("CSV-ATOMIC-ORIGINAL").ifPresent(customerRepository::delete);
            customerRepository.findByCustomerId("CSV-ATOMIC-NEXT").ifPresent(customerRepository::delete);
        }
    }

    private IngestionResult ingest(IngestionEntityType type, String csv) {
        return switch (type) {
            case CUSTOMER -> customers.importCsv(input(csv), "customers.csv");
            case ACCOUNT -> accounts.importCsv(input(csv), "accounts.csv");
            case TRANSACTION -> transactions.importCsv(input(csv), "transactions.csv");
        };
    }

    private ByteArrayInputStream input(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }
}
