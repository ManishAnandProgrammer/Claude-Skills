package com.example.app.common.limits;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.hibernate.validator.constraintvalidation.HibernateConstraintValidatorContext;
import org.springframework.core.env.Environment;

import java.time.LocalDate;
import java.util.Objects;

/** Like {@link LatestDateValidator}: the date comes from configuration, read from the environment. */
class EarliestDateValidator implements ConstraintValidator<EarliestDate, LocalDate> {

    private static final String PREFIX = "app.limits.earliest-date.";

    private final Environment environment;
    private String key;

    EarliestDateValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void initialize(EarliestDate constraint) {
        this.key = constraint.value();
    }

    @Override
    public boolean isValid(LocalDate value, ConstraintValidatorContext context) {
        LocalDate min = LocalDate.parse(environment.getRequiredProperty(PREFIX + key));
        context.unwrap(HibernateConstraintValidatorContext.class).addMessageParameter("min", min.toString());
        return Objects.isNull(value) || !value.isBefore(min);
    }
}
