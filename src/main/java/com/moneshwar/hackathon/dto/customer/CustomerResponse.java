package com.moneshwar.hackathon.dto.customer;

import com.moneshwar.hackathon.entity.enums.KycStatus;
import com.moneshwar.hackathon.entity.enums.RiskRating;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record CustomerResponse(
        Long id,
        String customerId,
        String firstName,
        String lastName,
        String gender,
        LocalDate dateOfBirth,
        String email,
        String phoneNumber,
        String city,
        String state,
        String country,
        String postalCode,
        String occupation,
        BigDecimal annualIncome,
        String maritalStatus,
        String educationLevel,
        String employmentStatus,
        LocalDate customerSince,
        String customerSegment,
        KycStatus kycStatus,
        RiskRating riskRating,
        boolean politicallyExposed,
        String preferredChannel,
        boolean emailVerified,
        boolean phoneVerified,
        int numComplaintsLastYear,
        Instant createdAt,
        Instant updatedAt
) {
}
