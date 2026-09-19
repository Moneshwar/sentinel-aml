package com.moneshwar.hackathon.repository;

import com.moneshwar.hackathon.entity.Alert;
import com.moneshwar.hackathon.entity.enums.AlertStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AlertRepository extends JpaRepository<Alert, Long> {

    Optional<Alert> findByAlertRef(String alertRef);

    boolean existsByAlertRef(String alertRef);

    java.util.List<Alert> findByDedupKeyStartingWithOrderByCreatedAtDesc(String prefix);

    boolean existsByDedupKey(String dedupKey);

    Page<Alert> findByStatusOrderByRiskScoreDesc(AlertStatus status, Pageable pageable);

    Page<Alert> findAllByOrderByRiskScoreDesc(Pageable pageable);

    Page<Alert> findByCustomer_Id(Long customerId, Pageable pageable);
}
