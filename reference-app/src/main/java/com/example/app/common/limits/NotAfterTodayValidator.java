package com.example.app.common.limits;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.RequiredArgsConstructor;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;

@RequiredArgsConstructor
class NotAfterTodayValidator implements ConstraintValidator<NotAfterToday, LocalDate> {

    private final NotAfterTodayProperties properties;
    private final Clock clock;

    @Override
    public boolean isValid(LocalDate value, ConstraintValidatorContext context) {
        LocalDate latest = LocalDate.now(clock).plus(properties.tolerance());
        return Objects.isNull(value) || !value.isAfter(latest);
    }
}
