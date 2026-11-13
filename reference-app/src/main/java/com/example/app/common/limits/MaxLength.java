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
 * or a Gurmukhi letter counts once). Every String in a request carries it, or a reason why not.
 */
@Documented
@Constraint(validatedBy = MaxLengthValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxLength {

    /** The key under {@code app.limits.text}, e.g. {@code "todo.title"}. */
    String value();

    String message() default "must be at most {max} characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
