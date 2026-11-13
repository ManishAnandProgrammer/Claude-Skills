package com.example.app.common.limits;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;

/** A Spring bean (created through the validator factory), so the tolerance and the clock are the application's. */
class NotAfterTodayValidator implements ConstraintValidator<NotAfterToday, LocalDate> {

    static final String TOLERANCE_KEY = "not-after-today.tolerance-days";

    private final Limits limits;
    private final Clock clock;

    NotAfterTodayValidator(Limits limits, Clock clock) {
        this.limits = limits;
        this.clock = clock;
    }

    @Override
    public boolean isValid(LocalDate value, ConstraintValidatorContext context) {
        LocalDate latest = LocalDate.now(clock).plusDays(limits.get(TOLERANCE_KEY));
        return Objects.isNull(value) || !value.isAfter(latest);
    }
}
