package com.example.app.common.limits;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.RequiredArgsConstructor;
import org.hibernate.validator.constraintvalidation.HibernateConstraintValidatorContext;

import java.util.Objects;

/** {@link ConfiguredMax} on a {@code Long} (sizes in bytes), whose limit may exceed an int. */
@RequiredArgsConstructor
class ConfiguredMaxLongValidator implements ConstraintValidator<ConfiguredMax, Long> {

    private final Limits limits;
    private String key;

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
