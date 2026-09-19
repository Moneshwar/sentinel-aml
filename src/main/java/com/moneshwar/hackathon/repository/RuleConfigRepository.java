package com.moneshwar.hackathon.repository;

import com.moneshwar.hackathon.entity.RuleConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RuleConfigRepository extends JpaRepository<RuleConfig, Long> {

    Optional<RuleConfig> findByRuleCode(String ruleCode);

    List<RuleConfig> findAllByEnabledTrue();
}