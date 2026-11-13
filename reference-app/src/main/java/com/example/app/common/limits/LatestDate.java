package com.example.app.common.limits;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The date is on or before {@code app.limits.latest-date.<value>} (yyyy-MM-dd), e.g. {@code "todo.due-date"}. */
@Documented
@Constraint(validatedBy = LatestDateValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface LatestDate {

    String value();

    String message() default "must be on or before {max}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
