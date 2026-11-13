package com.example.app.common.limits;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.hibernate.validator.constraintvalidation.HibernateConstraintValidatorContext;

import java.util.Objects;

/** A Spring bean (created through the validator factory), so the limit comes from configuration. */
class MaxLengthValidator implements ConstraintValidator<MaxLength, String> {

    private final Limits limits;
    private String key;

    MaxLengthValidator(Limits limits) {
        this.limits = limits;
    }

    @Override
    public void initialize(MaxLength constraint) {
        this.key = constraint.value();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        int max = limits.maxLength(key);
        context.unwrap(HibernateConstraintValidatorContext.class).addMessageParameter("max", max);
        return Objects.isNull(value) || value.codePointCount(0, value.length()) <= max;
    }
}
