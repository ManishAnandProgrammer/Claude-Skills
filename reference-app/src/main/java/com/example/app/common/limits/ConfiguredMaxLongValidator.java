package com.example.app.common.limits;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.hibernate.validator.constraintvalidation.HibernateConstraintValidatorContext;

import java.util.Objects;

/** {@link ConfiguredMax} on a {@code Long} (sizes in bytes), whose limit may exceed an int. */
class ConfiguredMaxLongValidator implements ConstraintValidator<ConfiguredMax, Long> {

    private final Limits limits;
    private String key;

    ConfiguredMaxLongValidator(Limits limits) {
        this.limits = limits;
    }

    @Override
    public void initialize(ConfiguredMax constraint) {
        this.key = constraint.value();
    }

    @Override
    public boolean isValid(Long value, ConstraintValidatorContext context) {
        long max = limits.getLong(key);
        context.unwrap(HibernateConstraintValidatorContext.class).addMessageParameter("max", max);
        return Objects.isNull(value) || value <= max;
    }
}
