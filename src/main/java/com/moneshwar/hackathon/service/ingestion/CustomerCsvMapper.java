package com.moneshwar.hackathon.service.ingestion;

import com.moneshwar.hackathon.dto.customer.CustomerRequest;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

@Component
public class CustomerCsvMapper {

    public CustomerRequest fromCsv(Map<String, String> row) {
        CustomerRequest request = new CustomerRequest();
        request.setCustomerId(CsvValueParser.str(row, "customer_id"));
        request.setFirstName(CsvValueParser.str(row, "first_name"));
        request.setLastName(CsvValueParser.str(row, "last_name"));
        request.setGender(CsvValueParser.str(row, "gender"));
        request.setDateOfBirth(CsvValueParser.date(row, "date_of_birth"));
        request.setEmail(CsvValueParser.str(row, "email"));
        request.setPhoneNumber(CsvValueParser.str(row, "phone_number"));
        request.setCity(CsvValueParser.str(row, "city"));
        request.setState(CsvValueParser.str(row, "state"));
        request.setCountry(upper(CsvValueParser.str(row, "country")));
        request.setPostalCode(CsvValueParser.str(row, "postal_code"));
        request.setOccupation(CsvValueParser.str(row, "occupation"));
        request.setAnnualIncome(CsvValueParser.decimal(row, "annual_income"));
        request.setMaritalStatus(CsvValueParser.str(row, "marital_status"));
        request.setEducationLevel(CsvValueParser.str(row, "education_level"));
        request.setEmploymentStatus(CsvValueParser.str(row, "employment_status"));
        request.setCustomerSince(CsvValueParser.date(row, "customer_since"));
        request.setCustomerSegment(CsvValueParser.str(row, "customer_segment"));
        request.setKycStatus(CsvValueParser.enumValue(row, "kyc_status",
                com.moneshwar.hackathon.entity.enums.KycStatus.class, null));
        request.setRiskRating(CsvValueParser.enumValue(row, "risk_rating",
                com.moneshwar.hackathon.entity.enums.RiskRating.class, null));
        request.setPoliticallyExposed(CsvValueParser.bool(row, "is_politically_exposed"));
        request.setPreferredChannel(CsvValueParser.str(row, "preferred_channel"));
        request.setEmailVerified(CsvValueParser.bool(row, "email_verified"));
        request.setPhoneVerified(CsvValueParser.bool(row, "phone_verified"));
        request.setNumComplaintsLastYear(CsvValueParser.integer(row, "num_complaints_last_year"));
        return request;
    }

    private String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }
}
