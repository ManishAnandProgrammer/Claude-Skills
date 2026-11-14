package com.example.app.common.limits;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.hibernate.validator.constraintvalidation.HibernateConstraintValidatorContext;

import java.util.Objects;

/**
 * A Spring bean (created through the validator factory), so the limit comes from configuration. It also
 * refuses the NUL character: every String with {@link MaxLength} passes here, and PostgreSQL refuses
 * U+0000 in text, which would otherwise surface as a 500 at the insert instead of a 400.
 */
class MaxLengthValidator implements ConstraintValidator<MaxLength, String> {

    private static final char NUL = '\u0000';
    private static final String NUL_MESSAGE = "{max-length.nul-character}";

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
        if (Objects.isNull(value)) {
            return true;
        }
        if (value.indexOf(NUL) >= 0) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(NUL_MESSAGE).addConstraintViolation();
            return false;
        }
        return value.codePointCount(0, value.length()) <= max;
    }
}
