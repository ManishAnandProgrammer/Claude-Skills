package com.example.app.common.limits;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.RequiredArgsConstructor;
import org.hibernate.validator.constraintvalidation.HibernateConstraintValidatorContext;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Also refuses what PostgreSQL can't store as sent: the NUL character (U+0000 in text is a 500 at the insert
 * instead of a 400) and a lone surrogate, half of a character, which UTF-8 can't carry (the driver would store
 * a '?' in its place, behind a 201).
 */
@RequiredArgsConstructor
class MaxLengthValidator implements ConstraintValidator<MaxLength, String> {

    private static final char NUL = '\u0000';
    private static final String NUL_MESSAGE = "{max-length.nul-character}";
    private static final String LONE_SURROGATE_MESSAGE = "{max-length.lone-surrogate}";

    private final Limits limits;
    private String key;

    @Override
    public void initialize(MaxLength constraint) {
        this.key = constraint.value();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        int max = limits.maxLength(key);
        context.unwrap(HibernateConstraintValidatorContext.class).addMessageParameter("max", max);
        if (Objects.isNull(value)) {
            return true;
        }
        if (value.indexOf(NUL) >= 0) {
            return refused(context, NUL_MESSAGE);
        }
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
            return refused(context, LONE_SURROGATE_MESSAGE);
        }
        return value.codePointCount(0, value.length()) <= max;
    }

    private static boolean refused(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        return false;
    }
}
