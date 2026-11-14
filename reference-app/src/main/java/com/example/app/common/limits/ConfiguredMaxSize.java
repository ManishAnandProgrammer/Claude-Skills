package com.example.app.common.limits;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The collection holds at most {@code app.limits.<value>} elements, e.g. {@code "todo.max-tags"}. A
 * {@code Set} counts each distinct element once, so a repeated id in a JSON array doesn't count twice.
 */
@Documented
@Constraint(validatedBy = ConfiguredMaxSizeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ConfiguredMaxSize {

    String value();

    String message() default "must have at most {max} items";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
