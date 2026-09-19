package com.moneshwar.hackathon.security;

import com.moneshwar.hackathon.entity.Account;
import com.moneshwar.hackathon.entity.Alert;
import com.moneshwar.hackathon.entity.Case;
import com.moneshwar.hackathon.entity.Customer;
import com.moneshwar.hackathon.entity.Transaction;
import com.moneshwar.hackathon.entity.enums.AlertStatus;
import com.moneshwar.hackathon.repository.AccountRepository;
import com.moneshwar.hackathon.repository.AlertRepository;
import com.moneshwar.hackathon.repository.AuditLogRepository;
import com.moneshwar.hackathon.repository.CaseRepository;
import com.moneshwar.hackathon.repository.CustomerRepository;
import com.moneshwar.hackathon.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "SENTINEL_ANALYST_PASSWORD=test-only-analyst-password")
@ActiveProfiles("test")
@Transactional
class SecurityWorkflowIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired CustomerRepository customers;
    @Autowired AccountRepository accounts;
    @Autowired TransactionRepository transactions;
    @Autowired AlertRepository alerts;
    @Autowired CaseRepository cases;
    @Autowired AuditLogRepository audit;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void requiresAuthenticationAndReportsJsonInsteadOfHtml() throws Exception {
        mvc.perform(get("/api/v1/alerts"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(401));
        mvc.perform(get("/api/v1/session").with(httpBasic("analyst", "wrong")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void configuredBasicLoginReturnsRolesButDoesNotCreateASession() throws Exception {
        var result = mvc.perform(get("/api/v1/session")
                        .with(httpBasic("analyst", "test-only-analyst-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("analyst"))
                .andExpect(jsonPath("$.roles[0]").value("ANALYST"))
                .andReturn();
        assertNull(result.getRequest().getSession(false));
        mvc.perform(get("/api/v1/session")).andExpect(status().isUnauthorized());
    }

    @Test
    void viewerCannotMutateIngestReadRawErrorsOrViewPersonalDetails() throws Exception {
        mvc.perform(post("/api/v1/transactions").with(user("viewer").roles("VIEWER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        mvc.perform(patch("/api/v1/alerts/A/status").with(user("viewer").roles("VIEWER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_REVIEW\"}"))
                .andExpect(status().isForbidden());
        for (String path : new String[]{"/api/v1/customers/C", "/api/v1/customers/by-id/1", "/api/v1/accounts/A", "/api/v1/transactions/T", "/api/v1/ingestion/errors", "/api/v1/audit"}) {
            mvc.perform(get(path).with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/alerts").with(user("viewer").roles("VIEWER"))).andExpect(status().isOk());
    }

    @Test
    void analystCannotIngestOrConfigureRules() throws Exception {
        mvc.perform(post("/api/v1/customers").with(user("analyst").roles("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/rules").with(user("analyst").roles("ANALYST")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/settings/rules").with(user("analyst").roles("ANALYST")))
                .andExpect(status().isForbidden());
    }

    @Test
    void personalFieldsAreMaskedAtApiLayerAndAuthorizedDetailsRemainVisible() throws Exception {
        var customer = customer("SEC-C1");
        mvc.perform(get("/api/v1/customers").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].firstName").value("***"))
                .andExpect(jsonPath("$.content[0].customerId").value("***"))
                .andExpect(jsonPath("$.content[0].lastName").value("***"))
                .andExpect(jsonPath("$.content[0].email").value("***"))
                .andExpect(jsonPath("$.content[0].phoneNumber").value("***"));
        mvc.perform(get("/api/v1/customers/SEC-C1").with(user("analyst").roles("ANALYST")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.firstName").value("Synthetic"))
                .andExpect(jsonPath("$.email").value("synthetic@example.test"));
        mvc.perform(get("/api/v1/customers/by-id/" + customer.getId()).with(user("analyst").roles("ANALYST")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.customerId").value("SEC-C1"));
    }

    @Test
    void counterpartyPiiIsMaskedOnGlobalAndAccountTransactionLists() throws Exception {
        var customer = customer("SEC-C1");
        var account = accounts.save(Account.builder().accountId("SEC-ACC1").customer(customer).currency("INR").build());
        transactions.save(Transaction.builder().transactionRef("SEC-T1").account(account).amount(BigDecimal.TEN)
                .currency("INR").transactionTime(Instant.now()).counterpartyName("Secret Name")
                .counterpartyAccount("Secret Account").description("Private narrative").build());
        for (String path : new String[]{"/api/v1/transactions", "/api/v1/accounts/SEC-ACC1/transactions"}) {
            mvc.perform(get(path).with(user("viewer").roles("VIEWER")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].counterpartyName").value("***"))
                    .andExpect(jsonPath("$.content[0].counterpartyAccount").value("***"))
                    .andExpect(jsonPath("$.content[0].description").doesNotExist());
        }
    }

    @Test
    void clearingRequiresReasonAndAuditsAuthenticatedIdentityDespiteSpoofedActor() throws Exception {
        alert("SEC-A1", customer("SEC-C1"));
        mvc.perform(patch("/api/v1/alerts/SEC-A1/status").with(user("real-analyst").roles("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CLEARED\"}"))
                .andExpect(status().isBadRequest());
        assertEquals(AlertStatus.OPEN, alerts.findByAlertRef("SEC-A1").orElseThrow().getStatus());
        assertEquals(0, audit.count());
        mvc.perform(patch("/api/v1/alerts/SEC-A1/status").with(user("real-analyst").roles("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"status":"CLEARED","disposition":"FALSE_POSITIVE",
                                 "dispositionReason":"Validated source of funds","actor":"forged-admin"}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dispositionReason").value("Validated source of funds"))
                .andExpect(jsonPath("$.triggeredRules[0]").value("CTR"));
        var entry = audit.findAll().getFirst();
        assertEquals("real-analyst", entry.getActor());
        assertEquals("Validated source of funds", entry.getDetails());
        assertNotNull(entry.getOccurredAt());
        assertEquals(1, alerts.count());
    }

    @Test
    void disposedAlertsCannotBeReopenedOrOverwriteDisposition() throws Exception {
        var alert = alert("SEC-A1", customer("SEC-C1"));
        alert.setStatus(AlertStatus.CLEARED);
        alert.setDispositionReason("Original reason");
        alerts.save(alert);
        mvc.perform(patch("/api/v1/alerts/SEC-A1/status").with(user("analyst").roles("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OPEN\"}"))
                .andExpect(status().isBadRequest());
        assertEquals("Original reason", alerts.findByAlertRef("SEC-A1").orElseThrow().getDispositionReason());
    }

    @Test
    void casesRejectUnrelatedAlerts() throws Exception {
        alert("SEC-A1", customer("SEC-C1"));
        alert("SEC-A2", customer("SEC-C2"));
        mvc.perform(post("/api/v1/cases").with(user("analyst").roles("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"title":"Cross-customer mistake","customerRef":"SEC-C1","alertRefs":["SEC-A2"]}
                                """))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/cases").with(user("analyst").roles("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"title":"Cross-customer mistake","alertRefs":["SEC-A1","SEC-A2"]}
                                """))
                .andExpect(status().isBadRequest());
        assertEquals(0, cases.count());
    }

    @Test
    void casesInferCustomerAndDoNotRequireCallerActor() throws Exception {
        alert("SEC-A1", customer("SEC-C1"));
        mvc.perform(post("/api/v1/cases").with(user("analyst").roles("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Review funds\",\"alertRefs\":[\"SEC-A1\"]}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.customerId").value("SEC-C1"));
        assertEquals("analyst", audit.findAll().getFirst().getActor());
    }

    @Test
    void closingCaseRequiresReasonAndPreventsReopening() throws Exception {
        cases.save(Case.builder().caseRef("SEC-CASE").title("Investigation").build());
        mvc.perform(patch("/api/v1/cases/SEC-CASE/status").with(user("analyst").roles("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/cases/SEC-CASE/status").with(user("analyst").roles("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"status":"SAR_FILED","dispositionReason":"Filed report with evidence"}
                                """))
                .andExpect(status().isOk());
        assertNotNull(cases.findByCaseRef("SEC-CASE").orElseThrow().getClosedAt());
        mvc.perform(patch("/api/v1/cases/SEC-CASE/status").with(user("analyst").roles("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OPEN\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void viewerAlertAndCaseDetailsSuppressNarrativesAndCustomerIds() throws Exception {
        var customer = customer("SEC-C1");
        var alert = alert("SEC-A1", customer);
        alert.setTitle("Investigation of Synthetic Person");
        alert.setExplanation("Private account information");
        alert.setDispositionReason("Named person's bank statement");
        alerts.save(alert);
        cases.save(Case.builder().caseRef("SEC-CASE").customer(customer).title("Synthetic Person")
                .description("Private identification document").dispositionReason("Confidential reason").build());
        mvc.perform(get("/api/v1/alerts/SEC-A1").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.customerId").value("***"))
                .andExpect(jsonPath("$.explanation").doesNotExist())
                .andExpect(jsonPath("$.dispositionReason").doesNotExist())
                .andExpect(jsonPath("$.title").value("AML review"))
                .andExpect(jsonPath("$.triggeredRules[0]").value("CTR"));
        mvc.perform(get("/api/v1/cases/SEC-CASE").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.customerId").value("***"))
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.dispositionReason").doesNotExist())
                .andExpect(jsonPath("$.title").value("Case investigation"));
        mvc.perform(get("/api/v1/alerts/SEC-A1").with(user("analyst").roles("ANALYST")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.explanation").value("Private account information"));
    }

    @Test
    void malformedHttpInputsReturnProblemBadRequest() throws Exception {
        mvc.perform(post("/api/v1/customers").with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mvc.perform(patch("/api/v1/alerts/A/status").with(user("analyst").roles("ANALYST"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INVALID\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        mvc.perform(multipart("/api/v1/ingestion/customers/csv").with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        mvc.perform(get("/api/v1/customers/by-id/not-a-number").with(user("analyst").roles("ANALYST")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
    }

    private Customer customer(String ref) {
        return customers.save(Customer.builder().customerId(ref).firstName("Synthetic").lastName("Person")
                .email("synthetic@example.test").phoneNumber("5550100").build());
    }

    private Alert alert(String ref, Customer customer) {
        return alerts.save(Alert.builder().alertRef(ref).customer(customer).ruleCode("CTR")
                .triggeredRules("CTR,HIGH_RISK_JURISDICTION").build());
    }
}
