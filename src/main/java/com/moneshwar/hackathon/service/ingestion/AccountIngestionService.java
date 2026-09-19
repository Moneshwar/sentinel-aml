package com.moneshwar.hackathon.service.ingestion;

import com.moneshwar.hackathon.dto.account.AccountRequest;
import com.moneshwar.hackathon.dto.ingestion.IngestionErrorView;
import com.moneshwar.hackathon.dto.ingestion.IngestionResult;
import com.moneshwar.hackathon.entity.Account;
import com.moneshwar.hackathon.entity.Customer;
import com.moneshwar.hackathon.entity.enums.AccountStatus;
import com.moneshwar.hackathon.entity.enums.IngestionEntityType;
import com.moneshwar.hackathon.entity.enums.IngestionErrorType;
import com.moneshwar.hackathon.entity.enums.IngestionSource;
import com.moneshwar.hackathon.exception.IngestionRecordException;
import com.moneshwar.hackathon.repository.AccountRepository;
import com.moneshwar.hackathon.repository.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AccountIngestionService {

    private static final Logger log = LoggerFactory.getLogger(AccountIngestionService.class);
    private static final int MAX_ERRORS_IN_RESULT = 200;

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final AccountCsvMapper accountCsvMapper;
    private final RecordValidator recordValidator;
    private final IngestionErrorRecorder errorRecorder;
    private final org.springframework.transaction.support.TransactionTemplate rowTransactions;

    public AccountIngestionService(AccountRepository accountRepository,
                                   CustomerRepository customerRepository,
                                   AccountCsvMapper accountCsvMapper,
                                   RecordValidator recordValidator,
                                   IngestionErrorRecorder errorRecorder,
                                    org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.accountCsvMapper = accountCsvMapper;
        this.recordValidator = recordValidator;
        this.errorRecorder = errorRecorder;
        this.rowTransactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    @Transactional
    public Account upsert(AccountRequest request) {
        return upsert(request, new HashMap<>());
    }

    private Account upsert(AccountRequest request, Map<String, Customer> customerCache) {
        List<String> violations = recordValidator.validate(request);
        if (!violations.isEmpty()) {
            throw new IngestionRecordException(IngestionErrorType.VALIDATION,
                    request.getAccountId(), null, String.join("; ", violations));
        }
        Customer customer = resolveCustomer(request.getCustomerId(), customerCache);
        Account account = accountRepository.findByAccountId(request.getAccountId())
                .orElseGet(Account::new);
        if (account.getId()!=null && !account.getCustomer().getId().equals(customer.getId()))
            throw new IngestionRecordException(IngestionErrorType.VALIDATION,request.getAccountId(),null,"An existing account cannot be reassigned to another customer");
        apply(request, account, customer);
        return accountRepository.save(account);
    }

    public IngestionResult importCsv(InputStream inputStream, String sourceName) {
        return importCsv(inputStream, sourceName, BatchIds.next(), IngestionProgress.NONE);
    }

    public IngestionResult importCsv(InputStream inputStream, String sourceName,
                                     String batchId, IngestionProgress progress) {
        class Counts { int total; int succeeded; int failed; }
        Counts counts = new Counts();
        List<IngestionErrorView> errors = new ArrayList<>();
        java.util.function.Consumer<IngestionErrorView> rejected = error -> {
            counts.failed++;
            if (errors.size() < MAX_ERRORS_IN_RESULT) errors.add(error);
        };
        try {
            CsvReader.forEach(inputStream, java.util.Set.of("account_id", "customer_id", "currency"), row -> {
                counts.total++;
                String reference = "row-" + row.recordNumber();
                String raw = row.values().toString();
                try {
                    if (row.error() != null) {
                        throw new IngestionRecordException(IngestionErrorType.MALFORMED, reference, raw, row.error());
                    }
                    AccountRequest request = accountCsvMapper.fromCsv(row.values());
                    if (request.getAccountId() != null) reference = request.getAccountId();
                    rowTransactions.execute(status -> upsert(request));
                    counts.succeeded++;
                } catch (IngestionRecordException exception) {
                    rejected.accept(errorRecorder.record(batchId, IngestionEntityType.ACCOUNT, sourceName,
                            reference, raw, exception.getErrorType(), exception.getMessage()));
                } catch (org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException exception) {
                    rejected.accept(errorRecorder.record(batchId, IngestionEntityType.ACCOUNT, sourceName,
                            reference, raw, IngestionErrorType.PERSISTENCE, exception.getMostSpecificCause().getMessage()));
                } catch (IllegalArgumentException exception) {
                    rejected.accept(errorRecorder.record(batchId, IngestionEntityType.ACCOUNT, sourceName,
                            reference, raw, IngestionErrorType.MALFORMED, exception.getMessage()));
                }
                progress.update(counts.total, counts.succeeded, counts.failed);
            });
        } catch (IOException exception) {
            // A broken CSV stream cannot safely resume; retain earlier committed rows.
            // Count its malformed trailing record/header as one rejected input item.
            counts.total++;
            rejected.accept(errorRecorder.record(batchId, IngestionEntityType.ACCOUNT, sourceName,
                    "file", null, IngestionErrorType.MALFORMED, "Unable to parse CSV: " + exception.getMessage()));
        }
        progress.update(counts.total, counts.succeeded, counts.failed);
        log.info("Account CSV import batch={} source={} total={} succeeded={} failed={}",
                batchId, sourceName, counts.total, counts.succeeded, counts.failed);
        return new IngestionResult(batchId, IngestionEntityType.ACCOUNT, sourceName,
                counts.total, counts.succeeded, counts.failed, errors);
    }

    public IngestionResult ingestBatch(List<AccountRequest> requests, IngestionSource source) {
        return ingestBatch(requests, source, BatchIds.next(), IngestionProgress.NONE);
    }

    public IngestionResult ingestBatch(List<AccountRequest> requests, IngestionSource source,
                                       String batchId, IngestionProgress progress) {
        int succeeded = 0;
        int failed = 0;
        List<IngestionErrorView> errors = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            AccountRequest request = requests.get(i);
            String reference = "index-" + i;
            String raw = String.valueOf(request);
            try {
                if (request == null) {
                    throw new IngestionRecordException(IngestionErrorType.MALFORMED, reference, raw, "record is null");
                }
                if (request.getAccountId() != null) {
                    reference = request.getAccountId();
                }
                rowTransactions.execute(status -> upsert(request));
                succeeded++;
            } catch (IngestionRecordException ex) {
                failed++;
                IngestionErrorView error = errorRecorder.record(batchId, IngestionEntityType.ACCOUNT, source.name(), reference,
                        raw, ex.getErrorType(), ex.getMessage());
                if (errors.size() < MAX_ERRORS_IN_RESULT) errors.add(error);
            } catch (org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException ex) {
                failed++;
                IngestionErrorView error = errorRecorder.record(batchId, IngestionEntityType.ACCOUNT, source.name(), reference,
                        raw, IngestionErrorType.PERSISTENCE, ex.getMostSpecificCause().getMessage());
                if (errors.size() < MAX_ERRORS_IN_RESULT) errors.add(error);
            }
            progress.update(i + 1, succeeded, failed);
        }
        return new IngestionResult(batchId, IngestionEntityType.ACCOUNT, source.name(), requests.size(),
                succeeded, failed, errors);
    }

    private Customer resolveCustomer(String customerId, Map<String, Customer> cache) {
        Customer cached = cache.get(customerId);
        if (cached != null) {
            return cached;
        }
        Customer customer = customerRepository.findByCustomerId(customerId)
                .orElseThrow(() -> new IngestionRecordException(IngestionErrorType.REFERENTIAL_INTEGRITY,
                        customerId, null, "Unknown customer_id: " + customerId));
        cache.put(customerId, customer);
        return customer;
    }

    private void apply(AccountRequest r, Account a, Customer customer) {
        a.setAccountId(r.getAccountId());
        a.setCustomer(customer);
        a.setAccountType(r.getAccountType());
        if (r.getRiskRating()!=null) a.setRiskRating(r.getRiskRating());
        if (r.getAccountStatus() != null) {
            a.setStatus(r.getAccountStatus());
        } else if (a.getStatus() == null) {
            a.setStatus(AccountStatus.ACTIVE);
        }
        a.setCurrency(r.getCurrency().toUpperCase());
        a.setOpenDate(r.getOpenDate());
        a.setCloseDate(r.getCloseDate());
        a.setBranchCode(r.getBranchCode());
        a.setBranchCity(r.getBranchCity());
        a.setCurrentBalance(r.getCurrentBalance());
        a.setAvgMonthlyBalance6m(r.getAvgMonthlyBalance6m());
        a.setCreditLimit(r.getCreditLimit());
        a.setCreditUtilizationPct(r.getCreditUtilizationPct());
        if (r.getOverdraftEnabled() != null) {
            a.setOverdraftEnabled(r.getOverdraftEnabled());
        }
        a.setCardType(r.getCardType());
        if (r.getJointAccount() != null) {
            a.setJointAccount(r.getJointAccount());
        }
        if (r.getNumLinkedDevices() != null) {
            a.setNumLinkedDevices(r.getNumLinkedDevices());
        }
        if (r.getMobileBankingEnrolled() != null) {
            a.setMobileBankingEnrolled(r.getMobileBankingEnrolled());
        }
        a.setLastLoginDate(r.getLastLoginDate());
        a.setAvgMonthlyTxnCount(r.getAvgMonthlyTxnCount());
        a.setAccountTier(r.getAccountTier());
    }

    private List<IngestionErrorView> cap(List<IngestionErrorView> errors) {
        return errors.size() <= MAX_ERRORS_IN_RESULT ? errors : errors.subList(0, MAX_ERRORS_IN_RESULT);
    }

    private String rootMessage(DataIntegrityViolationException ex) {
        return ex.getMostSpecificCause() != null ? ex.getMostSpecificCause().getMessage() : ex.getMessage();
    }
}
