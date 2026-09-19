package com.moneshwar.hackathon.mapper;

import com.moneshwar.hackathon.dto.account.AccountResponse;
import com.moneshwar.hackathon.entity.Account;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface AccountMapper {

    @Mapping(source = "customer.customerId", target = "customerId")
    @Mapping(source = "status", target = "accountStatus")
    AccountResponse toResponse(Account account);

    @Mapping(target = "customerId", constant = "***")
    @Mapping(source = "status", target = "accountStatus")
    AccountResponse toListResponse(Account account);
}
