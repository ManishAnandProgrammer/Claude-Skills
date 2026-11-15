package com.example.app.common.limits;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The number (an Integer or a Long) is at most {@code app.limits.<value>}, e.g. {@code "offset-page.max-size"}.
 * On a getter, a number derived from several fields (golden {@code PlanCreateRequest.getLengthInDays}).
 */
@Documented
@Constraint(validatedBy = {ConfiguredMaxValidator.class, ConfiguredMaxLongValidator.class})
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ConfiguredMax {

    String value();

    String message() default "must be at most {max}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
