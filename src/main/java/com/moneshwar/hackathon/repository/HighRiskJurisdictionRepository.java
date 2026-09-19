package com.moneshwar.hackathon.repository;

import com.moneshwar.hackathon.entity.HighRiskJurisdiction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface HighRiskJurisdictionRepository extends JpaRepository<HighRiskJurisdiction, Long> {
    Optional<HighRiskJurisdiction> findByCountryCodeAndEffectiveFrom(String country, Instant start);
    @org.springframework.data.jpa.repository.Query("select j from HighRiskJurisdiction j where j.effectiveFrom <= :at and (j.effectiveTo is null or j.effectiveTo > :at)")
    List<HighRiskJurisdiction> findActiveAt(@org.springframework.data.repository.query.Param("at") Instant at);

    List<HighRiskJurisdiction> findAllByEffectiveToIsNull();

    Optional<HighRiskJurisdiction> findFirstByCountryCodeAndEffectiveToIsNullOrderByEffectiveFromDesc(String countryCode);

    boolean existsByCountryCodeAndEffectiveToIsNull(String countryCode);

    // used by tests to purge
    void deleteAllByEffectiveFromBefore(Instant instant);
}