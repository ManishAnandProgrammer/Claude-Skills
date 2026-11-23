package com.example.app.common.limits;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.RequiredArgsConstructor;
import org.hibernate.validator.constraintvalidation.HibernateConstraintValidatorContext;

import java.math.BigDecimal;
import java.util.Objects;

@RequiredArgsConstructor
class ConfiguredMaxDecimalValidator implements ConstraintValidator<ConfiguredMax, BigDecimal> {

    private final Limits limits;
    private String key;

    @Override
    public void initialize(ConfiguredMax constraint) {
        this.key = constraint.value();
    }

    @Override
    public boolean isValid(BigDecimal value, ConstraintValidatorContext context) {
        BigDecimal max = limits.getDecimal(key);
        context.unwrap(HibernateConstraintValidatorContext.class).addMessageParameter("max", max.toPlainString());
        return Objects.isNull(value) || value.compareTo(max) <= 0;
    }
}
