package com.example.app.common.limits;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The text is at most {@code app.limits.text.<value>} characters (Unicode code points, so an emoji
 * or a Gurmukhi letter counts once), and holds no NUL character (U+0000, which PostgreSQL text can't
 * store). Every String in a request carries it, or a reason why not (a password takes
 * {@code @MaxUtf8Bytes}: it is hashed, never stored as text); on an element type too
 * ({@code Set<@MaxLength("search-query") String>}).
 */
@Documented
@Constraint(validatedBy = MaxLengthValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxLength {

    /** The key under {@code app.limits.text}, e.g. {@code "todo.title"}. */
    String value();

    String message() default "must be at most {max} characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
