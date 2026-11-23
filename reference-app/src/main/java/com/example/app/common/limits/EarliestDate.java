package com.example.app.common.limits;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A {@code LocalDate} reaches years PostgreSQL's {@code date} can't hold, which would surface as a 500 at the
 * database instead of a 400.
 */
@Documented
@Constraint(validatedBy = EarliestDateValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface EarliestDate {

    String value();

    String message() default "must be on or after {min}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
