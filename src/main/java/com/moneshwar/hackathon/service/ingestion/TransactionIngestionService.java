package com.moneshwar.hackathon.service.ingestion;

import com.moneshwar.hackathon.dto.ingestion.IngestionErrorView;
import com.moneshwar.hackathon.dto.ingestion.IngestionResult;
import com.moneshwar.hackathon.dto.transaction.TransactionRequest;
import com.moneshwar.hackathon.entity.Account;
import com.moneshwar.hackathon.entity.Transaction;
import com.moneshwar.hackathon.entity.enums.IngestionEntityType;
import com.moneshwar.hackathon.entity.enums.IngestionErrorType;
import com.moneshwar.hackathon.entity.enums.IngestionSource;
import com.moneshwar.hackathon.exception.IngestionRecordException;
import com.moneshwar.hackathon.repository.AccountRepository;
import com.moneshwar.hackathon.repository.TransactionRepository;
import com.moneshwar.hackathon.stream.TransactionEventPublisher;
import com.moneshwar.hackathon.stream.TransactionIngestedEvent;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class TransactionIngestionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionIngestionService.class);
    private static final int MAX_ERRORS_IN_RESULT = 200;

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final TransactionCsvMapper transactionCsvMapper;
    private final RecordValidator recordValidator;
    private final IngestionErrorRecorder errorRecorder;
    private final CurrencyNormalizer currencyNormalizer;
    private final com.moneshwar.hackathon.detection.DetectionEngine detection;
    private final com.moneshwar.hackathon.repository.CustomerRepository customers;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    public TransactionIngestionService(TransactionRepository transactionRepository,
                                       AccountRepository accountRepository,
                                       TransactionCsvMapper transactionCsvMapper,
                                       RecordValidator recordValidator,
                                       IngestionErrorRecorder errorRecorder,
                                       CurrencyNormalizer currencyNormalizer,
                                       com.moneshwar.hackathon.detection.DetectionEngine detection,
                                       com.moneshwar.hackathon.repository.CustomerRepository customers,
                                       org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.transactionCsvMapper = transactionCsvMapper;
        this.recordValidator = recordValidator;
        this.errorRecorder = errorRecorder;
        this.currencyNormalizer = currencyNormalizer;
        this.detection = detection;
        this.customers = customers;
        this.transactionTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    /** Each accepted transaction and all of its detection output commit atomically. */
    public Transaction ingest(TransactionRequest request, IngestionSource source) {
        validate(request);
        return transactionTemplate.execute(status -> persist(request,source,false).transaction());
    }

    public record StreamingResult(Transaction transaction, boolean created) {}

    public StreamingResult ingestStreamingWithOutcome(TransactionRequest request) {
        validate(request);
        try {
            return transactionTemplate.execute(status -> persist(request,IngestionSource.STREAM,true));
        } catch (DataIntegrityViolationException race) {
            // Another customer stream may have won the unique external-reference race.
            Transaction existing=transactionRepository.findByTransactionRef(request.getTransactionRef()).orElse(null);
            if(existing==null) throw race;
            verifyReplay(existing,request);
            return new StreamingResult(existing,false);
        }
    }
    public Transaction ingestStreaming(TransactionRequest request) { return ingestStreamingWithOutcome(request).transaction(); }

    private void validate(TransactionRequest request) {
        if(request==null) throw new IngestionRecordException(IngestionErrorType.MALFORMED,null,null,"record is null");
        List<String> violations=recordValidator.validate(request);
        if(!violations.isEmpty()) throw new IngestionRecordException(IngestionErrorType.VALIDATION,request.getTransactionRef(),null,String.join("; ",violations));
    }
    private StreamingResult persist(TransactionRequest request,IngestionSource source,boolean replay) {
        Account account=resolveAccount(request.getAccountId());
        customers.lockForDetection(account.getCustomer().getId()).orElseThrow();
        Optional<Transaction> existing=transactionRepository.findByTransactionRef(request.getTransactionRef());
        if(existing.isPresent()) {
            if(!replay) throw new IngestionRecordException(IngestionErrorType.DUPLICATE,request.getTransactionRef(),null,"transaction_ref already exists");
            verifyReplay(existing.get(),request);
            return new StreamingResult(existing.get(),false);
        }
        Transaction transaction=new Transaction();
        apply(request,transaction,account,source);
        Transaction saved=transactionRepository.saveAndFlush(transaction);
        detection.evaluate(saved);
        return new StreamingResult(saved,true);
    }
    private void verifyReplay(Transaction tx,TransactionRequest r) {
        boolean matches=tx.getAccount().getAccountId().equals(r.getAccountId()) && tx.getAmount().compareTo(r.getAmount())==0
            && tx.getCurrency().equalsIgnoreCase(r.getCurrency()) && tx.getTransactionTime().equals(r.getTransactionTime().truncatedTo(java.time.temporal.ChronoUnit.MICROS))
            && java.util.Objects.equals(tx.getDirection(),r.getDirection()) && java.util.Objects.equals(tx.getTransactionType(),r.getTransactionType())
            && java.util.Objects.equals(tx.getCounterpartyAccount(),r.getCounterpartyAccount()) && java.util.Objects.equals(tx.getCounterpartyName(),r.getCounterpartyName())
            && java.util.Objects.equals(tx.getCounterpartyCountry(),r.getCounterpartyCountry()) && java.util.Objects.equals(tx.getJurisdiction(),r.getJurisdiction())
            && java.util.Objects.equals(tx.getChannel(),r.getChannel()) && java.util.Objects.equals(tx.getDescription(),r.getDescription());
        if(!matches) throw new IngestionRecordException(IngestionErrorType.DUPLICATE,r.getTransactionRef(),null,"transaction_ref already exists with different data");
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
            CsvReader.forEach(inputStream, java.util.Set.of("transaction_ref", "account_id", "amount", "currency", "transaction_time"), row -> {
                counts.total++;
                String reference = "row-" + row.recordNumber();
                String raw = row.values().toString();
                try {
                    if (row.error() != null) {
                        throw new IngestionRecordException(IngestionErrorType.MALFORMED, reference, raw, row.error());
                    }
                    TransactionRequest request = transactionCsvMapper.fromCsv(row.values());
                    if (request.getTransactionRef() != null) reference = request.getTransactionRef();
                    ingest(request, IngestionSource.BULK_CSV);
                    counts.succeeded++;
                } catch (IngestionRecordException exception) {
                    rejected.accept(errorRecorder.record(batchId, IngestionEntityType.TRANSACTION, sourceName,
                            reference, raw, exception.getErrorType(), exception.getMessage()));
                } catch (org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException exception) {
                    rejected.accept(errorRecorder.record(batchId, IngestionEntityType.TRANSACTION, sourceName,
                            reference, raw, IngestionErrorType.PERSISTENCE, exception.getMostSpecificCause().getMessage()));
                } catch (IllegalArgumentException exception) {
                    rejected.accept(errorRecorder.record(batchId, IngestionEntityType.TRANSACTION, sourceName,
                            reference, raw, IngestionErrorType.MALFORMED, exception.getMessage()));
                }
                progress.update(counts.total, counts.succeeded, counts.failed);
            });
        } catch (IOException exception) {
            // A broken CSV stream cannot safely resume; retain earlier committed rows.
            // Count its malformed trailing record/header as one rejected input item.
            counts.total++;
            rejected.accept(errorRecorder.record(batchId, IngestionEntityType.TRANSACTION, sourceName,
                    "file", null, IngestionErrorType.MALFORMED, "Unable to parse CSV: " + exception.getMessage()));
        }
        progress.update(counts.total, counts.succeeded, counts.failed);
        log.info("Transaction CSV import batch={} source={} total={} succeeded={} failed={}",
                batchId, sourceName, counts.total, counts.succeeded, counts.failed);
        return new IngestionResult(batchId, IngestionEntityType.TRANSACTION, sourceName,
                counts.total, counts.succeeded, counts.failed, errors);
    }

    public IngestionResult ingestBatch(List<TransactionRequest> requests, IngestionSource source) {
        return ingestBatch(requests, source, BatchIds.next(), IngestionProgress.NONE);
    }

    public IngestionResult ingestBatch(List<TransactionRequest> requests, IngestionSource source,
                                       String batchId, IngestionProgress progress) {
        int succeeded = 0;
        List<IngestionErrorView> errors = new ArrayList<>();
        Map<String, Account> accountCache = new HashMap<>();
        for (int i = 0; i < requests.size(); i++) {
            TransactionRequest request = requests.get(i);
            String reference = "index-" + i;
            String raw = String.valueOf(request);
            try {
                if (request == null) {
                    throw new IngestionRecordException(IngestionErrorType.MALFORMED, reference, raw, "record is null");
                }
                if (request.getTransactionRef() != null) {
                    reference = request.getTransactionRef();
                }
                ingestCached(request, accountCache, source);
                succeeded++;
            } catch (IngestionRecordException ex) {
                errors.add(errorRecorder.record(batchId, IngestionEntityType.TRANSACTION, source.name(), reference,
                        raw, ex.getErrorType(), ex.getMessage()));
            } catch (DataIntegrityViolationException ex) {
                errors.add(errorRecorder.record(batchId, IngestionEntityType.TRANSACTION, source.name(), reference,
                        raw, IngestionErrorType.PERSISTENCE, rootMessage(ex)));
            } catch (IllegalArgumentException ex) {
                errors.add(errorRecorder.record(batchId, IngestionEntityType.TRANSACTION, source.name(), reference,
                        raw, IngestionErrorType.VALIDATION, ex.getMessage()));
            } catch (org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException ex) {
                errors.add(errorRecorder.record(batchId, IngestionEntityType.TRANSACTION, source.name(), reference,
                        raw, IngestionErrorType.PERSISTENCE, "Transaction could not be committed; retry this record"));
            }
            progress.update(i + 1, succeeded, i + 1 - succeeded);
        }
        return new IngestionResult(batchId, IngestionEntityType.TRANSACTION, source.name(), requests.size(),
                succeeded, errors.size(), cap(errors));
    }

    private Transaction ingestCached(TransactionRequest request, Map<String, Account> accountCache, IngestionSource source) {
        return ingest(request,source);
    }

    private Account resolveAccount(String accountId) {
        return accountRepository.findWithCustomerByAccountId(accountId)
                .or(() -> accountRepository.findByAccountId(accountId))
                .orElseThrow(() -> new IngestionRecordException(IngestionErrorType.REFERENTIAL_INTEGRITY,
                        accountId, null, "Unknown account_id: " + accountId));
    }

    private void apply(TransactionRequest r, Transaction t, Account account, IngestionSource source) {
        t.setTransactionRef(r.getTransactionRef());
        t.setAccount(account);
        t.setAmount(r.getAmount());
        String currency = r.getCurrency().toUpperCase(Locale.ROOT);
        t.setCurrency(currency);
        CurrencyNormalizer.NormalizedAmount normalized = currencyNormalizer.normalize(currency, r.getAmount(), r.getTransactionTime());
        t.setAmountBase(normalized.amountBase());
        t.setBaseCurrency(normalized.baseCurrency());
        t.setTransactionType(r.getTransactionType());
        t.setDirection(r.getDirection());
        t.setCounterpartyName(r.getCounterpartyName());
        t.setCounterpartyAccount(r.getCounterpartyAccount());
        t.setCounterpartyCountry(r.getCounterpartyCountry());
        t.setChannel(r.getChannel());
        t.setJurisdiction(r.getJurisdiction());
        t.setDescription(r.getDescription());
        t.setTransactionTime(r.getTransactionTime().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        t.setIngestionSource(source);
    }

    private TransactionIngestedEvent toEvent(Transaction transaction, Account account) {
        return new TransactionIngestedEvent(
                transaction.getId(),
                transaction.getTransactionRef(),
                account.getAccountId(),
                account.getCustomer().getCustomerId(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getAmountBase(),
                transaction.getBaseCurrency(),
                transaction.getTransactionTime(),
                transaction.getIngestionSource());
    }

    private List<IngestionErrorView> cap(List<IngestionErrorView> errors) {
        return errors.size() <= MAX_ERRORS_IN_RESULT ? errors : errors.subList(0, MAX_ERRORS_IN_RESULT);
    }

    private String rootMessage(DataIntegrityViolationException ex) {
        return ex.getMostSpecificCause() != null ? ex.getMostSpecificCause().getMessage() : ex.getMessage();
    }
}
