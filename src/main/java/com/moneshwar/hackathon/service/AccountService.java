package com.moneshwar.hackathon.service;

import com.moneshwar.hackathon.dto.account.AccountResponse;
import com.moneshwar.hackathon.dto.common.PageResponse;
import com.moneshwar.hackathon.entity.Account;
import com.moneshwar.hackathon.exception.ResourceNotFoundException;
import com.moneshwar.hackathon.mapper.AccountMapper;
import com.moneshwar.hackathon.repository.AccountRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AccountService {

    private final AccountRepository accountRepository;
    private final AccountMapper accountMapper;

    public AccountService(AccountRepository accountRepository, AccountMapper accountMapper) {
        this.accountRepository = accountRepository;
        this.accountMapper = accountMapper;
    }

    public PageResponse<AccountResponse> list(Pageable pageable) {
        return PageResponse.from(accountRepository.findAll(pageable), accountMapper::toListResponse);
    }

    public AccountResponse getByAccountId(String accountId) {
        Account account = find(accountId);
        return com.moneshwar.hackathon.security.PrivacyAccess.canReadDetails()
                ? accountMapper.toResponse(account) : accountMapper.toListResponse(account);
    }

    public Account find(String accountId) {
        return accountRepository.findByAccountId(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
    }
}
