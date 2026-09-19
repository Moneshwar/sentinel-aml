package com.moneshwar.hackathon.repository;

import com.moneshwar.hackathon.entity.Case;
import com.moneshwar.hackathon.entity.enums.CaseStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CaseRepository extends JpaRepository<Case, Long> {

    Optional<Case> findByCaseRef(String caseRef);

    boolean existsByCaseRef(String caseRef);

    Page<Case> findByStatus(CaseStatus status, Pageable pageable);

    Page<Case> findByCustomer_Id(Long customerId, Pageable pageable);
}
