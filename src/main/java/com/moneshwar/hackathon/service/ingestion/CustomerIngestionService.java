package com.moneshwar.hackathon.service.ingestion;

import com.moneshwar.hackathon.dto.customer.CustomerRequest;
import com.moneshwar.hackathon.dto.ingestion.IngestionErrorView;
import com.moneshwar.hackathon.dto.ingestion.IngestionResult;
import com.moneshwar.hackathon.entity.Customer;
import com.moneshwar.hackathon.entity.enums.IngestionEntityType;
import com.moneshwar.hackathon.entity.enums.IngestionErrorType;
import com.moneshwar.hackathon.entity.enums.IngestionSource;
import com.moneshwar.hackathon.entity.enums.RiskRating;
import com.moneshwar.hackathon.exception.IngestionRecordException;
import com.moneshwar.hackathon.repository.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Service
public class CustomerIngestionService {

    private static final Logger log = LoggerFactory.getLogger(CustomerIngestionService.class);
    private static final int MAX_ERRORS_IN_RESULT = 200;

    private final CustomerRepository customerRepository;
    private final CustomerCsvMapper customerCsvMapper;
    private final RecordValidator recordValidator;
    private final IngestionErrorRecorder errorRecorder;
    private final org.springframework.transaction.support.TransactionTemplate rowTransactions;

    public CustomerIngestionService(CustomerRepository customerRepository,
                                    CustomerCsvMapper customerCsvMapper,
                                    RecordValidator recordValidator,
                                    IngestionErrorRecorder errorRecorder,
                                    org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.customerRepository = customerRepository;
        this.customerCsvMapper = customerCsvMapper;
        this.recordValidator = recordValidator;
        this.errorRecorder = errorRecorder;
        this.rowTransactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    @Transactional
    public Customer upsert(CustomerRequest request) {
        List<String> violations = recordValidator.validate(request);
        if (!violations.isEmpty()) {
            throw new IngestionRecordException(IngestionErrorType.VALIDATION,
                    request.getCustomerId(), null, String.join("; ", violations));
        }
        Customer customer = customerRepository.findByCustomerId(request.getCustomerId())
                .orElseGet(Customer::new);
        apply(request, customer);
        return customerRepository.save(customer);
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
            CsvReader.forEach(inputStream, java.util.Set.of("customer_id", "first_name", "last_name"), row -> {
                counts.total++;
                String reference = "row-" + row.recordNumber();
                String raw = row.values().toString();
                try {
                    if (row.error() != null) {
                        throw new IngestionRecordException(IngestionErrorType.MALFORMED, reference, raw, row.error());
                    }
                    CustomerRequest request = customerCsvMapper.fromCsv(row.values());
                    if (request.getCustomerId() != null) reference = request.getCustomerId();
                    rowTransactions.execute(status -> upsert(request));
                    counts.succeeded++;
                } catch (IngestionRecordException exception) {
                    rejected.accept(errorRecorder.record(batchId, IngestionEntityType.CUSTOMER, sourceName,
                            reference, raw, exception.getErrorType(), exception.getMessage()));
                } catch (org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException exception) {
                    rejected.accept(errorRecorder.record(batchId, IngestionEntityType.CUSTOMER, sourceName,
                            reference, raw, IngestionErrorType.PERSISTENCE, exception.getMostSpecificCause().getMessage()));
                } catch (IllegalArgumentException exception) {
                    rejected.accept(errorRecorder.record(batchId, IngestionEntityType.CUSTOMER, sourceName,
                            reference, raw, IngestionErrorType.MALFORMED, exception.getMessage()));
                }
                progress.update(counts.total, counts.succeeded, counts.failed);
            });
        } catch (IOException exception) {
            // A broken CSV stream cannot safely resume; retain earlier committed rows.
            // Count its malformed trailing record/header as one rejected input item.
            counts.total++;
            rejected.accept(errorRecorder.record(batchId, IngestionEntityType.CUSTOMER, sourceName,
                    "file", null, IngestionErrorType.MALFORMED, "Unable to parse CSV: " + exception.getMessage()));
        }
        progress.update(counts.total, counts.succeeded, counts.failed);
        log.info("Customer CSV import batch={} source={} total={} succeeded={} failed={}",
                batchId, sourceName, counts.total, counts.succeeded, counts.failed);
        return new IngestionResult(batchId, IngestionEntityType.CUSTOMER, sourceName,
                counts.total, counts.succeeded, counts.failed, errors);
    }

    public IngestionResult ingestBatch(List<CustomerRequest> requests, IngestionSource source) {
        return ingestBatch(requests, source, BatchIds.next(), IngestionProgress.NONE);
    }

    public IngestionResult ingestBatch(List<CustomerRequest> requests, IngestionSource source,
                                       String batchId, IngestionProgress progress) {
        int succeeded = 0;
        int failed = 0;
        List<IngestionErrorView> errors = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            CustomerRequest request = requests.get(i);
            String reference = "index-" + i;
            String raw = String.valueOf(request);
            try {
                if (request == null) {
                    throw new IngestionRecordException(IngestionErrorType.MALFORMED, reference, raw, "record is null");
                }
                if (request.getCustomerId() != null) {
                    reference = request.getCustomerId();
                }
                rowTransactions.execute(status -> upsert(request));
                succeeded++;
            } catch (IngestionRecordException ex) {
                failed++;
                IngestionErrorView error = errorRecorder.record(batchId, IngestionEntityType.CUSTOMER, source.name(), reference,
                        raw, ex.getErrorType(), ex.getMessage());
                if (errors.size() < MAX_ERRORS_IN_RESULT) errors.add(error);
            } catch (org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException ex) {
                failed++;
                IngestionErrorView error = errorRecorder.record(batchId, IngestionEntityType.CUSTOMER, source.name(), reference,
                        raw, IngestionErrorType.PERSISTENCE, ex.getMostSpecificCause().getMessage());
                if (errors.size() < MAX_ERRORS_IN_RESULT) errors.add(error);
            }
            progress.update(i + 1, succeeded, failed);
        }
        return new IngestionResult(batchId, IngestionEntityType.CUSTOMER, source.name(), requests.size(),
                succeeded, failed, errors);
    }

    private void apply(CustomerRequest r, Customer c) {
        c.setCustomerId(r.getCustomerId());
        c.setFirstName(r.getFirstName());
        c.setLastName(r.getLastName());
        c.setGender(r.getGender());
        c.setDateOfBirth(r.getDateOfBirth());
        c.setEmail(r.getEmail());
        c.setPhoneNumber(r.getPhoneNumber());
        c.setCity(r.getCity());
        c.setState(r.getState());
        c.setCountry(r.getCountry());
        c.setPostalCode(r.getPostalCode());
        c.setOccupation(r.getOccupation());
        c.setAnnualIncome(r.getAnnualIncome());
        c.setMaritalStatus(r.getMaritalStatus());
        c.setEducationLevel(r.getEducationLevel());
        c.setEmploymentStatus(r.getEmploymentStatus());
        c.setCustomerSince(r.getCustomerSince());
        c.setCustomerSegment(r.getCustomerSegment());
        c.setKycStatus(r.getKycStatus());
        if (r.getRiskRating() != null) {
            c.setRiskRating(r.getRiskRating());
        } else if (c.getRiskRating() == null) {
            c.setRiskRating(RiskRating.LOW);
        }
        if (r.getPoliticallyExposed() != null) {
            c.setPoliticallyExposed(r.getPoliticallyExposed());
        }
        c.setPreferredChannel(r.getPreferredChannel());
        if (r.getEmailVerified() != null) {
            c.setEmailVerified(r.getEmailVerified());
        }
        if (r.getPhoneVerified() != null) {
            c.setPhoneVerified(r.getPhoneVerified());
        }
        if (r.getNumComplaintsLastYear() != null) {
            c.setNumComplaintsLastYear(r.getNumComplaintsLastYear());
        }
    }

    private List<IngestionErrorView> cap(List<IngestionErrorView> errors) {
        return errors.size() <= MAX_ERRORS_IN_RESULT ? errors : errors.subList(0, MAX_ERRORS_IN_RESULT);
    }

    private String rootMessage(DataIntegrityViolationException ex) {
        return ex.getMostSpecificCause() != null ? ex.getMostSpecificCause().getMessage() : ex.getMessage();
    }
}
