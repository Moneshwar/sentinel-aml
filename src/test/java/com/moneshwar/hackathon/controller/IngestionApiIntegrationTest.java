package com.moneshwar.hackathon.controller;

import com.moneshwar.hackathon.dto.account.AccountRequest;
import com.moneshwar.hackathon.dto.customer.CustomerRequest;
import com.moneshwar.hackathon.repository.TransactionRepository;
import com.moneshwar.hackathon.service.ingestion.AccountIngestionService;
import com.moneshwar.hackathon.service.ingestion.CustomerIngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@org.springframework.security.test.context.support.WithMockUser(username = "admin", roles = "ADMIN")
class IngestionApiIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private CustomerIngestionService customerIngestionService;

    @Autowired
    private AccountIngestionService accountIngestionService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private com.moneshwar.hackathon.repository.ExchangeRateRepository exchangeRateRepository;

    @Autowired private com.moneshwar.hackathon.service.ingestion.IngestionJobWorker worker;
    @Autowired private tools.jackson.databind.ObjectMapper mapper;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        exchangeRateRepository.save(com.moneshwar.hackathon.entity.ExchangeRate.builder()
                .fromCurrency("USD").toCurrency("INR").rate(new java.math.BigDecimal("83.25"))
                .effectiveFrom(java.time.Instant.EPOCH).build());
    }

    @Test
    void createsTransactionOverRest() throws Exception {
        seed("CUST_92001", "ACC_92001");
        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transactionJson("TXN_92001", "ACC_92001", "1500.00", "INR")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionRef").value("TXN_92001"))
                .andExpect(jsonPath("$.accountId").value("ACC_92001"))
                .andExpect(jsonPath("$.customerId").value("CUST_92001"));
    }

    @Test
    void streamEndpointIsIdempotent() throws Exception {
        seed("CUST_92002", "ACC_92002");
        String payload = transactionJson("TXN_92002", "ACC_92002", "2500.00", "INR");

        mockMvc.perform(post("/api/v1/transactions/stream")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/transactions/stream")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk());

        Assertions.assertEquals(1, transactionRepository.count());
    }

    @Test
    void rejectsInvalidBodyWithBadRequest() throws Exception {
        String payload = """
                {"transactionRef":"TXN_92003","accountId":"ACC_92003","currency":"INR",
                 "transactionTime":"2026-09-01T10:00:00Z"}
                """;
        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.amount").exists());
    }

    @Test
    void unknownAccountReturnsUnprocessableEntity() throws Exception {
        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transactionJson("TXN_92004", "ACC_MISSING", "100.00", "INR")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorType").value("REFERENTIAL_INTEGRITY"));
    }

    @Test
    void duplicateTransactionReturnsConflict() throws Exception {
        seed("CUST_92005", "ACC_92005");
        String payload = transactionJson("TXN_92005", "ACC_92005", "100.00", "INR");

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorType").value("DUPLICATE"));
    }

    @Test
    void csvUploadIngestsRowsAndReportsErrors() throws Exception {
        String csv = """
                customer_id,first_name,last_name,email,risk_rating
                CUST_92006,Marie,Curie,marie@example.com,HIGH
                CUST_92007,Rosalind,Franklin,rosalind@example.com,LOW
                """;
        MockMultipartFile file = new MockMultipartFile("file", "customers.csv",
                "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        var accepted = mockMvc.perform(multipart("/api/v1/ingestion/customers/csv").file(file))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED")).andReturn();
        String jobId = mapper.readTree(accepted.getResponse().getContentAsString()).get("jobId").asText();
        worker.processNext();
        mockMvc.perform(get("/api/v1/ingestion/jobs/" + jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.result.succeeded").value(2))
                .andExpect(jsonPath("$.result.failed").value(0));
    }

    @Test
    void listsPersistedIngestionErrors() throws Exception {
        String csv = """
                customer_id,first_name,last_name,annual_income
                CUST_92008,Ada,Lovelace,not-a-number
                """;
        MockMultipartFile file = new MockMultipartFile("file", "customers.csv",
                "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/v1/ingestion/customers/csv").file(file))
                .andExpect(status().isAccepted());
        worker.processNext();

        mockMvc.perform(get("/api/v1/ingestion/errors").param("batchId", "nonexistent"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/ingestion/errors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.errorType=='MALFORMED')]").exists());
    }

    private void seed(String customerId, String accountId) {
        CustomerRequest customer = new CustomerRequest();
        customer.setCustomerId(customerId);
        customer.setFirstName("Seed");
        customer.setLastName("Customer");
        customerIngestionService.upsert(customer);

        AccountRequest account = new AccountRequest();
        account.setAccountId(accountId);
        account.setCustomerId(customerId);
        account.setCurrency("INR");
        accountIngestionService.upsert(account);
    }

    private String transactionJson(String ref, String accountId, String amount, String currency) {
        return """
                {"transactionRef":"%s","accountId":"%s","amount":%s,"currency":"%s",
                 "transactionTime":"2026-09-01T10:00:00Z"}
                """.formatted(ref, accountId, amount, currency);
    }
}
