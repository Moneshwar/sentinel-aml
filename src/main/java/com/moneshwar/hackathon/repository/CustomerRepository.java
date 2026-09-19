package com.moneshwar.hackathon.repository;

import com.moneshwar.hackathon.entity.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select c from Customer c where c.id = :id")
    Optional<Customer> lockForDetection(@org.springframework.data.repository.query.Param("id") Long id);

    Optional<Customer> findByCustomerId(String customerId);

    List<Customer> findAllByCustomerIdIn(Collection<String> customerIds);

    boolean existsByCustomerId(String customerId);

    Page<Customer> findByRiskRating(String riskRating, Pageable pageable);
}
