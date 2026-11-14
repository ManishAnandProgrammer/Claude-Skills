package com.example.app.common.limits;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.hibernate.validator.constraintvalidation.HibernateConstraintValidatorContext;

import java.util.Collection;
import java.util.Objects;

class ConfiguredMaxSizeValidator implements ConstraintValidator<ConfiguredMaxSize, Collection<?>> {

    private final Limits limits;
    private String key;

    ConfiguredMaxSizeValidator(Limits limits) {
        this.limits = limits;
    }

    @Override
    public void initialize(ConfiguredMaxSize constraint) {
        this.key = constraint.value();
    }

    @Override
    public boolean isValid(Collection<?> value, ConstraintValidatorContext context) {
        int max = limits.get(key);
        context.unwrap(HibernateConstraintValidatorContext.class).addMessageParameter("max", max);
        return Objects.isNull(value) || value.size() <= max;
    }
}
