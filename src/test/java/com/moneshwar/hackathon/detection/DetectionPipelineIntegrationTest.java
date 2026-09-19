package com.moneshwar.hackathon.detection;

import com.moneshwar.hackathon.dto.account.AccountRequest;
import com.moneshwar.hackathon.dto.customer.CustomerRequest;
import com.moneshwar.hackathon.dto.transaction.TransactionRequest;
import com.moneshwar.hackathon.entity.*;
import com.moneshwar.hackathon.entity.enums.*;
import com.moneshwar.hackathon.repository.*;
import com.moneshwar.hackathon.service.ingestion.*;
import com.moneshwar.hackathon.service.config.RuleSettingsService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:pipeline;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class DetectionPipelineIntegrationTest {
    @Autowired TransactionIngestionService ingestion;
    @Autowired CustomerIngestionService customers;
    @Autowired AccountIngestionService accounts;
    @Autowired AlertRepository alerts;
    @Autowired TransactionRepository transactions;
    @Autowired ExchangeRateRepository rates;
    @Autowired HighRiskJurisdictionRepository jurisdictions;
    @Autowired SanctionedCounterpartyRepository counterparties;
    @Autowired PlatformTransactionManager manager;
    @Autowired RuleSettingsService settings;
    String prefix,account;
    Instant start=Instant.parse("2026-09-19T06:00:00Z");
    @BeforeEach void seed() {
        if(rates.findTopByFromCurrencyAndToCurrencyOrderByEffectiveFromDesc("USD","INR").isEmpty())
            rates.save(ExchangeRate.builder().fromCurrency("USD").toCurrency("INR").rate(new BigDecimal("83.25")).effectiveFrom(Instant.EPOCH).build());
        prefix=UUID.randomUUID().toString(); account="A-"+prefix;
        CustomerRequest c=new CustomerRequest(); c.setCustomerId("C-"+prefix); c.setFirstName("Synthetic"); c.setLastName("Pipeline"); customers.upsert(c);
        AccountRequest a=new AccountRequest(); a.setAccountId(account); a.setCustomerId(c.getCustomerId()); a.setCurrency("INR"); accounts.upsert(a);
        settings.update("STRUCTURING",true,RuleSettingsService.defaults().get("STRUCTURING"));
    }
    TransactionRequest payment(String suffix,String amount,String direction,long seconds) {
        TransactionRequest r=new TransactionRequest(); r.setTransactionRef(prefix+"-"+suffix); r.setAccountId(account);
        r.setAmount(new BigDecimal(amount)); r.setCurrency("USD"); r.setTransactionTime(start.plusSeconds(seconds)); r.setDirection(direction); return r;
    }
    List<Alert> findings() {
        return new TransactionTemplate(manager).execute(s -> alerts.findAll().stream()
            .filter(a->a.getCustomer()!=null && a.getCustomer().getCustomerId().equals("C-"+prefix)).toList());
    }
    @Test void csvRunsDetectionAndAggregatesEvidenceInOneAlert() {
        String csv="transaction_ref,account_id,amount,currency,direction,transaction_time\n";
        for(int i=0;i<4;i++) csv+=prefix+"-"+i+","+account+",9500,USD,IN,"+start.plusSeconds(i*3600)+"\n";
        var result=ingestion.importCsv(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),"synthetic.csv");
        assertEquals(4,result.succeeded()); assertEquals(0,result.failed());
        assertEquals(1,findings().size()); assertTrue(findings().getFirst().getTriggeredRules().contains("STRUCTURING"));
        new TransactionTemplate(manager).execute(s -> {
            Alert a=alerts.findByAlertRef(findings().getFirst().getAlertRef()).orElseThrow();
            assertEquals(4,a.getTransactions().size()); assertEquals(30,a.getRiskScore()); assertFalse(a.getExplanation().isBlank()); return null;
        });
    }
    @Test void depositAndOutflowCombineRuleWeights() {
        ingestion.ingest(payment("deposit","10000","IN",0),IngestionSource.API);
        ingestion.ingest(payment("out","8500","OUT",1800),IngestionSource.API);
        assertEquals(1,findings().size());
        assertEquals(Set.of("CTR","RAPID_MOVEMENT"),Set.of(findings().getFirst().getTriggeredRules().split(",")));
        assertEquals(45,findings().getFirst().getRiskScore());
    }
    @Test void lateDepositReevaluatesAlreadyStoredOutgoingTransaction() {
        ingestion.ingest(payment("out","8500","OUT",1800),IngestionSource.API);
        ingestion.ingest(payment("deposit","10000","IN",0),IngestionSource.API);
        assertTrue(findings().getFirst().getTriggeredRules().contains("RAPID_MOVEMENT"));
    }
    @Test void sanctionsAlwaysFlagSmallAmounts() {
        var party=new SanctionedCounterparty(); party.setIdentifier("PARTY-"+prefix.toUpperCase()); party.setEnabled(true); counterparties.save(party);
        TransactionRequest request=payment("small","1","OUT",0); request.setCounterpartyAccount(party.getIdentifier());
        ingestion.ingest(request,IngestionSource.API);
        assertEquals("HIGH_RISK_JURISDICTION",findings().getFirst().getTriggeredRules());
    }
    @Test void missingRateRejectsWithoutPersistingTransaction() {
        TransactionRequest request=payment("missing-fx","1","OUT",0); request.setCurrency("ZZZ");
        assertThrows(RuntimeException.class,()->ingestion.ingest(request,IngestionSource.API));
        assertFalse(transactions.existsByTransactionRef(request.getTransactionRef())); assertTrue(findings().isEmpty());
    }
    @Test void concurrentStreamReplaysCreateOneTransactionAndOneAlert() throws Exception {
        var request=payment("concurrent","10000","IN",0);
        ExecutorService pool=Executors.newFixedThreadPool(4);
        try {
            List<Callable<TransactionIngestionService.StreamingResult>> calls=new ArrayList<>();
            for(int i=0;i<8;i++) calls.add(()->ingestion.ingestStreamingWithOutcome(request));
            var futures=pool.invokeAll(calls); int created=0; Set<Long> ids=new HashSet<>();
            for(var future:futures) { var r=future.get(20,TimeUnit.SECONDS); created+=r.created()?1:0; ids.add(r.transaction().getId()); }
            assertEquals(1,created); assertEquals(1,ids.size()); assertEquals(1,findings().size());
        } finally { pool.shutdownNow(); }
    }
    @Test void conflictingReplayIsRejected() {
        var request=payment("replay","10000","IN",0); ingestion.ingestStreaming(request);
        request.setAmount(new BigDecimal("10001")); assertThrows(RuntimeException.class,()->ingestion.ingestStreaming(request));
        assertEquals(1,findings().size());
    }
    @Test void changingRuleConfigurationAffectsNextTransactionWithoutRestart() {
        settings.update("STRUCTURING",false,RuleSettingsService.defaults().get("STRUCTURING"));
        for(int i=0;i<3;i++) ingestion.ingest(payment("off"+i,"9500","IN",i*3600),IngestionSource.API);
        assertTrue(findings().isEmpty());
        settings.update("STRUCTURING",true,RuleSettingsService.defaults().get("STRUCTURING"));
        ingestion.ingest(payment("on","9500","IN",4*3600),IngestionSource.API);
        assertEquals(1,findings().size()); assertTrue(findings().getFirst().getTriggeredRules().contains("STRUCTURING"));
    }
}
