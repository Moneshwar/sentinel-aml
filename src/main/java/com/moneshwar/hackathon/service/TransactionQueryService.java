package com.moneshwar.hackathon.service;

import com.moneshwar.hackathon.dto.common.PageResponse;
import com.moneshwar.hackathon.dto.transaction.TransactionResponse;
import com.moneshwar.hackathon.entity.Account;
import com.moneshwar.hackathon.entity.Transaction;
import com.moneshwar.hackathon.exception.ResourceNotFoundException;
import com.moneshwar.hackathon.mapper.TransactionMapper;
import com.moneshwar.hackathon.repository.TransactionRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TransactionQueryService {

    private final TransactionRepository transactionRepository;
    private final AccountService accountService;
    private final TransactionMapper transactionMapper;

    public TransactionQueryService(TransactionRepository transactionRepository,
                                   AccountService accountService,
                                   TransactionMapper transactionMapper) {
        this.transactionRepository = transactionRepository;
        this.accountService = accountService;
        this.transactionMapper = transactionMapper;
    }

    public PageResponse<TransactionResponse> list(Pageable pageable) {
        return PageResponse.from(transactionRepository.findAll(pageable), transactionMapper::toListResponse);
    }

    public PageResponse<TransactionResponse> listByAccount(String accountId, Pageable pageable) {
        Account account = accountService.find(accountId);
        return PageResponse.from(transactionRepository.findByAccount_Id(account.getId(), pageable),
                transactionMapper::toListResponse);
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    public TransactionResponse getByReference(String transactionRef) {
        return transactionMapper.toResponse(find(transactionRef));
    }

    public Transaction find(String transactionRef) {
        return transactionRepository.findByTransactionRef(transactionRef)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", transactionRef));
    }
}
