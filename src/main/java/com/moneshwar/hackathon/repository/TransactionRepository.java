package com.moneshwar.hackathon.repository;

import com.moneshwar.hackathon.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths={"account", "account.customer"})
    Optional<Transaction> findByTransactionRef(String transactionRef);

    List<Transaction> findByTransactionRefIn(java.util.Collection<String> transactionRefs);

    boolean existsByTransactionRef(String transactionRef);

    Page<Transaction> findByAccount_Id(Long accountId, Pageable pageable);

    List<Transaction> findByAccount_IdAndTransactionTimeBetween(Long accountId, Instant from, Instant to);

    long countByAccount_IdAndTransactionTimeBetween(Long accountId, Instant from, Instant to);

    @Query("select t from Transaction t where t.account.customer.id = :customerId "
            + "and t.transactionTime between :from and :to order by t.transactionTime asc")
    List<Transaction> findByCustomerAndWindow(@Param("customerId") Long customerId,
                                              @Param("from") Instant from,
                                              @Param("to") Instant to);
}
