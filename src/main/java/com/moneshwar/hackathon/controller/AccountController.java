package com.moneshwar.hackathon.controller;

import com.moneshwar.hackathon.dto.account.AccountRequest;
import com.moneshwar.hackathon.dto.account.AccountResponse;
import com.moneshwar.hackathon.dto.common.PageResponse;
import com.moneshwar.hackathon.dto.transaction.TransactionResponse;
import com.moneshwar.hackathon.entity.Account;
import com.moneshwar.hackathon.mapper.AccountMapper;
import com.moneshwar.hackathon.service.AccountService;
import com.moneshwar.hackathon.service.TransactionQueryService;
import com.moneshwar.hackathon.service.ingestion.AccountIngestionService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;
    private final TransactionQueryService transactionQueryService;
    private final AccountIngestionService accountIngestionService;
    private final AccountMapper accountMapper;

    public AccountController(AccountService accountService,
                             TransactionQueryService transactionQueryService,
                             AccountIngestionService accountIngestionService,
                             AccountMapper accountMapper) {
        this.accountService = accountService;
        this.transactionQueryService = transactionQueryService;
        this.accountIngestionService = accountIngestionService;
        this.accountMapper = accountMapper;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody AccountRequest request) {
        Account saved = accountIngestionService.upsert(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(accountMapper.toResponse(saved));
    }

    @GetMapping
    public PageResponse<AccountResponse> list(Pageable pageable) {
        return accountService.list(pageable);
    }

    @GetMapping("/{accountId}")
    public AccountResponse get(@PathVariable String accountId) {
        return accountService.getByAccountId(accountId);
    }

    @GetMapping("/{accountId}/transactions")
    public PageResponse<TransactionResponse> transactions(@PathVariable String accountId, Pageable pageable) {
        return transactionQueryService.listByAccount(accountId, pageable);
    }
}
