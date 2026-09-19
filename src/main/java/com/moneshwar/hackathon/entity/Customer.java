package com.moneshwar.hackathon.entity;

import com.moneshwar.hackathon.entity.enums.KycStatus;
import com.moneshwar.hackathon.entity.enums.RiskRating;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
@Table(name = "customers")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false, unique = true, length = 64)
    private String customerId;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(length = 20)
    private String gender;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    private String email;

    @Column(name = "phone_number", length = 50)
    private String phoneNumber;

    @Column(length = 100)
    private String city;

    @Column(length = 100)
    private String state;

    @Column(length = 3)
    private String country;

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    @Column(length = 150)
    private String occupation;

    @Column(name = "annual_income", precision = 19, scale = 2)
    private BigDecimal annualIncome;

    @Column(name = "marital_status", length = 30)
    private String maritalStatus;

    @Column(name = "education_level", length = 50)
    private String educationLevel;

    @Column(name = "employment_status", length = 30)
    private String employmentStatus;

    @Column(name = "customer_since")
    private LocalDate customerSince;

    @Column(name = "customer_segment", length = 30)
    private String customerSegment;

    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_status", length = 30)
    private KycStatus kycStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_rating", nullable = false, length = 20)
    @Builder.Default
    private RiskRating riskRating = RiskRating.LOW;

    @Column(name = "is_politically_exposed", nullable = false)
    @Builder.Default
    private boolean politicallyExposed = false;

    @Column(name = "preferred_channel", length = 50)
    private String preferredChannel;

    @Column(name = "email_verified", nullable = false)
    @Builder.Default
    private boolean emailVerified = false;

    @Column(name = "phone_verified", nullable = false)
    @Builder.Default
    private boolean phoneVerified = false;

    @Column(name = "num_complaints_last_year", nullable = false)
    @Builder.Default
    private int numComplaintsLastYear = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL)
    @Builder.Default
    private List<Account> accounts = new ArrayList<>();

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
