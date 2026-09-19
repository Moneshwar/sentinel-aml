package com.moneshwar.hackathon.service.ingestion;

import com.moneshwar.hackathon.dto.account.AccountRequest;
import com.moneshwar.hackathon.dto.customer.CustomerRequest;
import com.moneshwar.hackathon.dto.ingestion.IngestionResult;
import com.moneshwar.hackathon.dto.transaction.TransactionRequest;
import com.moneshwar.hackathon.entity.ExchangeRate;
import com.moneshwar.hackathon.entity.Transaction;
import com.moneshwar.hackathon.entity.enums.IngestionErrorType;
import com.moneshwar.hackathon.entity.enums.IngestionSource;
import com.moneshwar.hackathon.entity.enums.RiskRating;
import com.moneshwar.hackathon.exception.IngestionRecordException;
import com.moneshwar.hackathon.repository.ExchangeRateRepository;
import com.moneshwar.hackathon.repository.IngestionErrorRepository;
import com.moneshwar.hackathon.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class IngestionIntegrationTest {

    @Autowired
    private CustomerIngestionService customerIngestionService;

    @Autowired
    private AccountIngestionService accountIngestionService;

    @Autowired
    private TransactionIngestionService transactionIngestionService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private IngestionErrorRepository ingestionErrorRepository;

    @Autowired
    private ExchangeRateRepository exchangeRateRepository;

    @org.junit.jupiter.api.BeforeEach
    void seedThresholdCurrency() {
        exchangeRateRepository.save(ExchangeRate.builder()
                .fromCurrency("USD").toCurrency("INR").rate(new BigDecimal("83.25"))
                .effectiveFrom(Instant.EPOCH).build());
    }

    @Test
    void importsValidCustomerCsv() {
        String csv = """
                customer_id,first_name,last_name,email,risk_rating,country
                CUST_90001,Ada,Lovelace,ada@example.com,HIGH,IN
                CUST_90002,Alan,Turing,,LOW,GB
                """;
        IngestionResult result = customerIngestionService.importCsv(stream(csv), "customers.csv");
        assertEquals(2, result.totalRecords());
        assertEquals(2, result.succeeded());
        assertEquals(0, result.failed());
    }

    @Test
    void rejectsMalformedCustomerRowAndLogsError() {
        String csv = """
                customer_id,first_name,last_name,annual_income
                CUST_90003,Grace,Hopper,not-a-number
                """;
        IngestionResult result = customerIngestionService.importCsv(stream(csv), "customers.csv");
        assertEquals(0, result.succeeded());
        assertEquals(1, result.failed());
        assertEquals(IngestionErrorType.MALFORMED, result.errors().get(0).errorType());
        assertEquals(1, ingestionErrorRepository.countByBatchId(result.batchId()));
    }

    @Test
    void rejectsCustomerRowFailingBeanValidation() {
        String csv = """
                customer_id,first_name,last_name
                CUST_90004,,Missing
                """;
        IngestionResult result = customerIngestionService.importCsv(stream(csv), "customers.csv");
        assertEquals(1, result.failed());
        assertEquals(IngestionErrorType.VALIDATION, result.errors().get(0).errorType());
    }

    @Test
    void accountImportEnforcesReferentialIntegrity() {
        String csv = """
                account_id,customer_id,currency
                ACC_90001,CUST_DOES_NOT_EXIST,INR
                """;
        IngestionResult result = accountIngestionService.importCsv(stream(csv), "accounts.csv");
        assertEquals(0, result.succeeded());
        assertEquals(1, result.failed());
        assertEquals(IngestionErrorType.REFERENTIAL_INTEGRITY, result.errors().get(0).errorType());
    }

    @Test
    void transactionRejectsUnknownAccount() {
        TransactionRequest request = transaction("TXN_91000", "ACC_MISSING", "1000.00", "INR", Instant.now());
        IngestionRecordException ex = assertThrows(IngestionRecordException.class,
                () -> transactionIngestionService.ingest(request, IngestionSource.API));
        assertEquals(IngestionErrorType.REFERENTIAL_INTEGRITY, ex.getErrorType());
    }

    @Test
    void duplicateTransactionReferenceIsRejected() {
        seedCustomerAndAccount("CUST_91001", "ACC_91001");
        TransactionRequest request = transaction("TXN_91001", "ACC_91001", "1000.00", "INR", Instant.now());
        transactionIngestionService.ingest(request, IngestionSource.API);

        IngestionRecordException ex = assertThrows(IngestionRecordException.class,
                () -> transactionIngestionService.ingest(request, IngestionSource.API));
        assertEquals(IngestionErrorType.DUPLICATE, ex.getErrorType());
    }

    @Test
    void streamingIngestionIsIdempotent() {
        seedCustomerAndAccount("CUST_91002", "ACC_91002");
        TransactionRequest request = transaction("TXN_91002", "ACC_91002", "1000.00", "INR", Instant.now());

        Transaction first = transactionIngestionService.ingestStreaming(request);
        Transaction replay = transactionIngestionService.ingestStreaming(request);

        assertEquals(first.getId(), replay.getId());
        assertEquals(1, transactionRepository.count());
    }

    @Test
    void foreignCurrencyAmountIsNormalizedToBaseCurrency() {
        exchangeRateRepository.save(ExchangeRate.builder()
                .fromCurrency("USD").toCurrency("INR").rate(new BigDecimal("83.25")).build());
        seedCustomerAndAccount("CUST_91003", "ACC_91003");

        Transaction transaction = transactionIngestionService.ingest(
                transaction("TXN_91003", "ACC_91003", "100.00", "USD", Instant.now()), IngestionSource.API);

        assertNotNull(transaction.getAmountBase());
        assertEquals(new BigDecimal("8325.00"), transaction.getAmountBase());
        assertEquals("INR", transaction.getBaseCurrency());
    }

    private void seedCustomerAndAccount(String customerId, String accountId) {
        CustomerRequest customer = new CustomerRequest();
        customer.setCustomerId(customerId);
        customer.setFirstName("Test");
        customer.setLastName("Customer");
        customer.setRiskRating(RiskRating.MEDIUM);
        customerIngestionService.upsert(customer);

        AccountRequest account = new AccountRequest();
        account.setAccountId(accountId);
        account.setCustomerId(customerId);
        account.setCurrency("INR");
        accountIngestionService.upsert(account);
    }

    private TransactionRequest transaction(String ref, String accountId, String amount, String currency, Instant when) {
        TransactionRequest request = new TransactionRequest();
        request.setTransactionRef(ref);
        request.setAccountId(accountId);
        request.setAmount(new BigDecimal(amount));
        request.setCurrency(currency);
        request.setTransactionTime(when);
        return request;
    }

    private ByteArrayInputStream stream(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }
}
