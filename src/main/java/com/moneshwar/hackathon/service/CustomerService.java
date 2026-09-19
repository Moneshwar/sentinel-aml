package com.moneshwar.hackathon.service;

import com.moneshwar.hackathon.dto.account.AccountResponse;
import com.moneshwar.hackathon.dto.common.PageResponse;
import com.moneshwar.hackathon.dto.customer.CustomerResponse;
import com.moneshwar.hackathon.entity.Customer;
import com.moneshwar.hackathon.exception.ResourceNotFoundException;
import com.moneshwar.hackathon.mapper.AccountMapper;
import com.moneshwar.hackathon.mapper.CustomerMapper;
import com.moneshwar.hackathon.repository.AccountRepository;
import com.moneshwar.hackathon.repository.CustomerRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final AccountRepository accountRepository;
    private final CustomerMapper customerMapper;
    private final AccountMapper accountMapper;

    public CustomerService(CustomerRepository customerRepository,
                           AccountRepository accountRepository,
                           CustomerMapper customerMapper,
                           AccountMapper accountMapper) {
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
        this.customerMapper = customerMapper;
        this.accountMapper = accountMapper;
    }

    public PageResponse<CustomerResponse> list(Pageable pageable) {
        return PageResponse.from(customerRepository.findAll(pageable), customerMapper::toListResponse);
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    public CustomerResponse getByCustomerId(String customerId) {
        return customerMapper.toResponse(find(customerId));
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    public CustomerResponse getById(Long id) {
        return customerMapper.toResponse(customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", String.valueOf(id))));
    }

    public PageResponse<AccountResponse> accounts(String customerId, Pageable pageable) {
        Customer customer = find(customerId);
        return PageResponse.from(accountRepository.findByCustomer_Id(customer.getId(), pageable), accountMapper::toListResponse);
    }

    public Customer find(String customerId) {
        return customerRepository.findByCustomerId(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", customerId));
    }
}
