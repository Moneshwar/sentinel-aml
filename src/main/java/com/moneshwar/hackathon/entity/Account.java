package com.moneshwar.hackathon.entity;

import com.moneshwar.hackathon.entity.enums.AccountStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "accounts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, unique = true, length = 64)
    private String accountId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "account_type", length = 30)
    private String accountType;

    @Enumerated(EnumType.STRING)
    @Column(name="risk_rating",nullable=false,length=20)
    @Builder.Default
    private com.moneshwar.hackathon.entity.enums.RiskRating riskRating = com.moneshwar.hackathon.entity.enums.RiskRating.LOW;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", nullable = false, length = 30)
    @Builder.Default
    private AccountStatus status = AccountStatus.ACTIVE;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "INR";

    @Column(name = "open_date")
    private LocalDate openDate;

    @Column(name = "close_date")
    private LocalDate closeDate;

    @Column(name = "branch_code", length = 30)
    private String branchCode;

    @Column(name = "branch_city", length = 100)
    private String branchCity;

    @Column(name = "current_balance", precision = 19, scale = 2)
    private BigDecimal currentBalance;

    @Column(name = "avg_monthly_balance_6m", precision = 19, scale = 2)
    private BigDecimal avgMonthlyBalance6m;

    @Column(name = "credit_limit", precision = 19, scale = 2)
    private BigDecimal creditLimit;

    @Column(name = "credit_utilization_pct", precision = 7, scale = 4)
    private BigDecimal creditUtilizationPct;

    @Column(name = "overdraft_enabled", nullable = false)
    @Builder.Default
    private boolean overdraftEnabled = false;

    @Column(name = "card_type", length = 30)
    private String cardType;

    @Column(name = "is_joint_account", nullable = false)
    @Builder.Default
    private boolean jointAccount = false;

    @Column(name = "num_linked_devices", nullable = false)
    @Builder.Default
    private int numLinkedDevices = 0;

    @Column(name = "mobile_banking_enrolled", nullable = false)
    @Builder.Default
    private boolean mobileBankingEnrolled = false;

    @Column(name = "last_login_date")
    private LocalDate lastLoginDate;

    @Column(name = "avg_monthly_txn_count")
    private Integer avgMonthlyTxnCount;

    @Column(name = "account_tier", length = 30)
    private String accountTier;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "account", cascade = CascadeType.ALL)
    @Builder.Default
    private List<Transaction> transactions = new ArrayList<>();

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
