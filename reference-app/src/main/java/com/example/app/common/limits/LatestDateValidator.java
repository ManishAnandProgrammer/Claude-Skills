package com.example.app.common.limits;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.RequiredArgsConstructor;
import org.hibernate.validator.constraintvalidation.HibernateConstraintValidatorContext;
import org.springframework.core.env.Environment;

import java.time.LocalDate;
import java.util.Objects;

@RequiredArgsConstructor
class LatestDateValidator implements ConstraintValidator<LatestDate, LocalDate> {

    private static final String PREFIX = "app.limits.latest-date.";

    private final Environment environment;
    private String key;

    @Override
    public void initialize(LatestDate constraint) {
        this.key = constraint.value();
    }

    @Override
    public boolean isValid(LocalDate value, ConstraintValidatorContext context) {
        LocalDate max = LocalDate.parse(environment.getRequiredProperty(PREFIX + key));
        context.unwrap(HibernateConstraintValidatorContext.class).addMessageParameter("max", max.toString());
        return Objects.isNull(value) || !value.isAfter(max);
    }
}
