package com.example.app.common.limits;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A calendar date that must not be in the future, such as the day time was spent.
 *
 * <p>The server is time-zone free: a moment is an {@code Instant}, a calendar date is a
 * {@code LocalDate} exactly as the client sent it, and neither is ever converted on the server. So
 * the server can't know the user's "today". Its own is the UTC day of the application's
 * {@code Clock}, and every time zone is at most 14 hours ahead of UTC: a date is accepted up to UTC
 * today plus {@code app.limits.not-after-today.tolerance}, which no real user's today exceeds.
 * The client enforces the exact rule in the user's zone. Use it instead of {@code @PastOrPresent},
 * which compares with the UTC day alone and refuses a user east of UTC their own today.</p>
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
