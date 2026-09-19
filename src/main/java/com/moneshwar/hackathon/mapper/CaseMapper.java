package com.moneshwar.hackathon.mapper;

import com.moneshwar.hackathon.dto.cases.CaseResponse;
import com.moneshwar.hackathon.entity.Alert;
import com.moneshwar.hackathon.entity.Case;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;
import java.util.Set;

@Mapper(componentModel = "spring")
public interface CaseMapper {

    @Mapping(source = "customer.customerId", target = "customerId")
    @Mapping(source = "alerts", target = "alertRefs")
    CaseResponse toResponse(Case aCase);

    @Mapping(target = "customerId", constant = "***")
    @Mapping(target = "title", constant = "Case investigation")
    @Mapping(target = "description", ignore = true)
    @Mapping(target = "dispositionReason", ignore = true)
    @Mapping(target = "disposition", ignore = true)
    @Mapping(source = "alerts", target = "alertRefs")
    CaseResponse toListResponse(Case aCase);

    default List<String> mapAlerts(Set<Alert> alerts) {
        if (alerts == null) {
            return List.of();
        }
        return alerts.stream().map(Alert::getAlertRef).toList();
    }
}
