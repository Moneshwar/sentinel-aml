package com.moneshwar.hackathon.dto.account;

import com.moneshwar.hackathon.entity.enums.AccountStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class AccountRequest {

    @NotBlank
    @Size(max = 64)
    private String accountId;

    @NotBlank
    @Size(max = 64)
    private String customerId;

    @Size(max = 30)
    private String accountType;

    private com.moneshwar.hackathon.entity.enums.RiskRating riskRating;

    private AccountStatus accountStatus;

    @NotBlank
    @Pattern(regexp = "^[A-Za-z]{3}$", message = "currency must be a 3-letter ISO code")
    private String currency;

    private LocalDate openDate;

    private LocalDate closeDate;

    @Size(max = 30)
    private String branchCode;

    @Size(max = 100)
    private String branchCity;

    private BigDecimal currentBalance;

    private BigDecimal avgMonthlyBalance6m;

    private BigDecimal creditLimit;

    private BigDecimal creditUtilizationPct;

    private Boolean overdraftEnabled;

    @Size(max = 30)
    private String cardType;

    private Boolean jointAccount;

    private Integer numLinkedDevices;

    private Boolean mobileBankingEnrolled;

    private LocalDate lastLoginDate;

    private Integer avgMonthlyTxnCount;

    @Size(max = 30)
    private String accountTier;
}
