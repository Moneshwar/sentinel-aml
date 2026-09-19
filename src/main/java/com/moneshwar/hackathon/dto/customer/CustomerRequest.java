package com.moneshwar.hackathon.dto.customer;

import com.moneshwar.hackathon.entity.enums.KycStatus;
import com.moneshwar.hackathon.entity.enums.RiskRating;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class CustomerRequest {

    @NotBlank
    @Size(max = 64)
    private String customerId;

    @NotBlank
    @Size(max = 100)
    private String firstName;

    @NotBlank
    @Size(max = 100)
    private String lastName;

    @Size(max = 20)
    private String gender;

    private LocalDate dateOfBirth;

    @Email
    @Size(max = 255)
    private String email;

    @Size(max = 50)
    private String phoneNumber;

    @Size(max = 100)
    private String city;

    @Size(max = 100)
    private String state;

    @Size(max = 3)
    private String country;

    @Size(max = 20)
    private String postalCode;

    @Size(max = 150)
    private String occupation;

    private BigDecimal annualIncome;

    @Size(max = 30)
    private String maritalStatus;

    @Size(max = 50)
    private String educationLevel;

    @Size(max = 30)
    private String employmentStatus;

    private LocalDate customerSince;

    @Size(max = 30)
    private String customerSegment;

    private KycStatus kycStatus;

    private RiskRating riskRating;

    private Boolean politicallyExposed;

    @Size(max = 50)
    private String preferredChannel;

    private Boolean emailVerified;

    private Boolean phoneVerified;

    private Integer numComplaintsLastYear;
}
