package com.moneshwar.hackathon.entity;

import com.moneshwar.hackathon.entity.enums.IngestionSource;
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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "transactions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_ref", nullable = false, unique = true, length = 100)
    private String transactionRef;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "amount_base", precision = 19, scale = 2)
    private BigDecimal amountBase;

    @Column(name = "base_currency", length = 3)
    private String baseCurrency;

    @Column(name = "transaction_type", length = 30)
    private String transactionType;

    @Column(length = 10)
    private String direction;

    @Column(name = "counterparty_name")
    private String counterpartyName;

    @Column(name = "counterparty_account", length = 100)
    private String counterpartyAccount;

    @Column(name = "counterparty_country", length = 3)
    private String counterpartyCountry;

    @Column(length = 50)
    private String channel;

    @Column(length = 3)
    private String jurisdiction;

    @Column(length = 500)
    private String description;

    @Column(name = "transaction_time", nullable = false)
    private Instant transactionTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "ingestion_source", nullable = false, length = 20)
    @Builder.Default
    private IngestionSource ingestionSource = IngestionSource.API;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (ingestionSource == null) {
            ingestionSource = IngestionSource.API;
        }
    }
}
