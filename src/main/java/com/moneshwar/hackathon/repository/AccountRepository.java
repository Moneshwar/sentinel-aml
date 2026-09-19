package com.moneshwar.hackathon.repository;

import com.moneshwar.hackathon.entity.Account;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByAccountId(String accountId);

    @EntityGraph(attributePaths = "customer")
    Optional<Account> findWithCustomerByAccountId(String accountId);

    List<Account> findAllByAccountIdIn(Collection<String> accountIds);

    boolean existsByAccountId(String accountId);

    Page<Account> findByCustomer_Id(Long customerId, Pageable pageable);

    long countByCustomer_Id(Long customerId);
}
