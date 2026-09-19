package com.moneshwar.hackathon.dto.account;

import com.moneshwar.hackathon.entity.enums.AccountStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record AccountResponse(
        Long id,
        String accountId,
        String customerId,
        String accountType,
        com.moneshwar.hackathon.entity.enums.RiskRating riskRating,
        AccountStatus accountStatus,
        String currency,
        LocalDate openDate,
        LocalDate closeDate,
        String branchCode,
        String branchCity,
        BigDecimal currentBalance,
        BigDecimal avgMonthlyBalance6m,
        BigDecimal creditLimit,
        BigDecimal creditUtilizationPct,
        boolean overdraftEnabled,
        String cardType,
        boolean jointAccount,
        int numLinkedDevices,
        boolean mobileBankingEnrolled,
        LocalDate lastLoginDate,
        Integer avgMonthlyTxnCount,
        String accountTier,
        Instant createdAt,
        Instant updatedAt
) {
}
