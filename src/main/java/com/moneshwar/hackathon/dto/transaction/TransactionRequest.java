package com.moneshwar.hackathon.dto.transaction;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
public class TransactionRequest {

    @NotBlank
    @Size(max = 100)
    private String transactionRef;

    @NotBlank
    @Size(max = 64)
    private String accountId;

    @NotNull
    @DecimalMin(value = "0.0", inclusive = false, message = "amount must be greater than zero")
    @jakarta.validation.constraints.Digits(integer=17, fraction=2)
    private BigDecimal amount;

    @NotBlank
    @Pattern(regexp = "^[A-Za-z]{3}$", message = "currency must be a 3-letter ISO code")
    private String currency;

    @Size(max = 30)
    private String transactionType;

    @Size(max = 10)
    private String direction;

    @Size(max = 255)
    private String counterpartyName;

    @Size(max = 100)
    private String counterpartyAccount;

    @Size(max = 3)
    private String counterpartyCountry;

    @Size(max = 50)
    private String channel;

    @Size(max = 3)
    private String jurisdiction;

    @Size(max = 500)
    private String description;

    @NotNull(message = "transactionTime is required")
    private Instant transactionTime;
}
