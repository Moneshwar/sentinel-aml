package com.moneshwar.hackathon.mapper;

import com.moneshwar.hackathon.dto.transaction.TransactionResponse;
import com.moneshwar.hackathon.entity.Transaction;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface TransactionMapper {

    @Mapping(source = "account.accountId", target = "accountId")
    @Mapping(source = "account.customer.customerId", target = "customerId")
    TransactionResponse toResponse(Transaction transaction);

    @Mapping(source = "account.accountId", target = "accountId")
    @Mapping(target = "customerId", constant = "***")
    @Mapping(target = "counterpartyName", constant = "***")
    @Mapping(target = "counterpartyAccount", constant = "***")
    @Mapping(target = "description", ignore = true)
    TransactionResponse toListResponse(Transaction transaction);
}
