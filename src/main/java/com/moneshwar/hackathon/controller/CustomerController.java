package com.moneshwar.hackathon.controller;

import com.moneshwar.hackathon.dto.account.AccountResponse;
import com.moneshwar.hackathon.dto.common.PageResponse;
import com.moneshwar.hackathon.dto.customer.CustomerRequest;
import com.moneshwar.hackathon.dto.customer.CustomerResponse;
import com.moneshwar.hackathon.entity.Customer;
import com.moneshwar.hackathon.mapper.CustomerMapper;
import com.moneshwar.hackathon.service.CustomerService;
import com.moneshwar.hackathon.service.ingestion.CustomerIngestionService;
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
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private final CustomerService customerService;
    private final CustomerIngestionService customerIngestionService;
    private final CustomerMapper customerMapper;

    public CustomerController(CustomerService customerService,
                              CustomerIngestionService customerIngestionService,
                              CustomerMapper customerMapper) {
        this.customerService = customerService;
        this.customerIngestionService = customerIngestionService;
        this.customerMapper = customerMapper;
    }

    @PostMapping
    public ResponseEntity<CustomerResponse> create(@Valid @RequestBody CustomerRequest request) {
        Customer saved = customerIngestionService.upsert(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(customerMapper.toResponse(saved));
    }

    @GetMapping
    public PageResponse<CustomerResponse> list(Pageable pageable) {
        return customerService.list(pageable);
    }

    @GetMapping("/{customerId}")
    public CustomerResponse get(@PathVariable String customerId) {
        return customerService.getByCustomerId(customerId);
    }

    @GetMapping("/{customerId}/accounts")
    public PageResponse<AccountResponse> accounts(@PathVariable String customerId, Pageable pageable) {
        return customerService.accounts(customerId, pageable);
    }

    @GetMapping("/by-id/{id}")
    public CustomerResponse getById(@PathVariable Long id) {
        return customerService.getById(id);
    }
}
