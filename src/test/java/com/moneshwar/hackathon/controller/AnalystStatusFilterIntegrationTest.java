package com.moneshwar.hackathon.controller;

import com.moneshwar.hackathon.entity.Alert;
import com.moneshwar.hackathon.entity.Case;
import com.moneshwar.hackathon.entity.enums.AlertStatus;
import com.moneshwar.hackathon.entity.enums.CaseStatus;
import com.moneshwar.hackathon.repository.AlertRepository;
import com.moneshwar.hackathon.repository.CaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@org.springframework.security.test.context.support.WithMockUser(username = "analyst", roles = "ANALYST")
class AnalystStatusFilterIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private CaseRepository caseRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @Test
    void filtersAlertsAndOrdersByRiskBeforePagination() throws Exception {
        alert("FILTER-LOW", AlertStatus.OPEN, 20);
        alert("FILTER-HIGH", AlertStatus.OPEN, 90);
        alert("FILTER-CLOSED", AlertStatus.CLOSED, 100);

        mockMvc.perform(get("/api/v1/alerts").param("status", " open ").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].alertRef").value("FILTER-HIGH"))
                .andExpect(jsonPath("$.content[0].status").value("OPEN"));

        mockMvc.perform(get("/api/v1/alerts").param("status", "OPEN")
                        .param("size", "1").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].alertRef").value("FILTER-LOW"));
    }

    @Test
    void unfilteredAlertsRemainOrderedByRisk() throws Exception {
        alert("ALL-LOW", AlertStatus.OPEN, 10);
        alert("ALL-HIGH", AlertStatus.CLOSED, 95);

        mockMvc.perform(get("/api/v1/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].alertRef").value("ALL-HIGH"))
                .andExpect(jsonPath("$.content[1].alertRef").value("ALL-LOW"));
    }

    @Test
    void rejectsUnknownAlertStatusAsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/alerts").param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"));
    }

    @Test
    void filtersCasesUsingTheRequestedEnumStatus() throws Exception {
        caseRepository.save(Case.builder().caseRef("CASE-FILTER-OPEN")
                .title("Open case").status(CaseStatus.OPEN).build());
        caseRepository.save(Case.builder().caseRef("CASE-FILTER-INVESTIGATING")
                .title("Active investigation").status(CaseStatus.INVESTIGATING).build());

        mockMvc.perform(get("/api/v1/cases").param("status", " investigating "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].caseRef").value("CASE-FILTER-INVESTIGATING"))
                .andExpect(jsonPath("$.content[0].status").value("INVESTIGATING"));

        mockMvc.perform(get("/api/v1/cases").param("status", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].caseRef").value("CASE-FILTER-OPEN"));
    }

    @Test
    void rejectsUnknownCaseStatusAsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/cases").param("status", "CLEARED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"));
    }

    private void alert(String reference, AlertStatus status, int riskScore) {
        alertRepository.save(Alert.builder().alertRef(reference).ruleCode("CTR")
                .title("Filter regression fixture").status(status).riskScore(riskScore).build());
    }
}
