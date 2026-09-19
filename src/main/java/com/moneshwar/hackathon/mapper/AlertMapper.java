package com.moneshwar.hackathon.mapper;

import com.moneshwar.hackathon.dto.alert.AlertResponse;
import com.moneshwar.hackathon.entity.Alert;
import com.moneshwar.hackathon.entity.Transaction;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;
import java.util.Set;

@Mapper(componentModel = "spring")
public interface AlertMapper {

    @Mapping(source = "customer.customerId", target = "customerId")
    @Mapping(source = "account.accountId", target = "accountId")
    @Mapping(source = "transactions", target = "transactionRefs")
    AlertResponse toResponse(Alert alert);

    @Mapping(target = "customerId", constant = "***")
    @Mapping(target = "accountId", constant = "***")
    @Mapping(target = "title", constant = "AML review")
    @Mapping(target = "explanation", ignore = true)
    @Mapping(target = "dispositionReason", ignore = true)
    @Mapping(target = "disposition", ignore = true)
    @Mapping(source = "transactions", target = "transactionRefs")
    AlertResponse toListResponse(Alert alert);

    default List<String> mapRules(String rules) {
        return rules == null || rules.isBlank() ? List.of() : java.util.Arrays.stream(rules.split(","))
                .map(String::trim).filter(rule -> !rule.isBlank()).distinct().toList();
    }

    default List<String> mapTransactions(Set<Transaction> transactions) {
        if (transactions == null) {
            return List.of();
        }
        return transactions.stream().map(Transaction::getTransactionRef).toList();
    }
}
