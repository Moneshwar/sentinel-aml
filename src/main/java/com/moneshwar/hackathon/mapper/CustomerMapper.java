package com.moneshwar.hackathon.mapper;

import com.moneshwar.hackathon.dto.customer.CustomerResponse;
import com.moneshwar.hackathon.entity.Customer;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface CustomerMapper {

    CustomerResponse toResponse(Customer customer);

    @Mapping(target = "customerId", constant = "***")
    @Mapping(target = "firstName", constant = "***")
    @Mapping(target = "lastName", constant = "***")
    @Mapping(target = "email", constant = "***")
    @Mapping(target = "phoneNumber", constant = "***")
    @Mapping(target = "dateOfBirth", ignore = true)
    @Mapping(target = "postalCode", ignore = true)
    @Mapping(target = "annualIncome", ignore = true)
    CustomerResponse toListResponse(Customer customer);
}
