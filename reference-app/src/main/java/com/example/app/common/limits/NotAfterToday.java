package com.example.app.common.limits;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The server can't know the user's today, so a date is accepted up to UTC today plus
 * {@code app.limits.not-after-today.tolerance}. Use it instead of {@code @PastOrPresent}, which compares with
 * the UTC day alone and refuses a user east of UTC their own today.
 */
@Documented
@Constraint(validatedBy = NotAfterTodayValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface NotAfterToday {

    String message() default "must not be in the future";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
