package com.moneshwar.hackathon.service.ingestion;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RecordValidator {

    private final Validator validator;

    public RecordValidator(Validator validator) {
        this.validator = validator;
    }

    public List<String> validate(Object bean) {
        return validator.validate(bean).stream()
                .map(this::describe)
                .toList();
    }

    private String describe(ConstraintViolation<?> violation) {
        return violation.getPropertyPath() + ": " + violation.getMessage();
    }
}
