package com.example.app.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.AssertTrue;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A rule across fields of a request, on a hidden boolean getter that is true while the rule holds (a range
 * in order, a filter with the order it needs). The violation names {@link #reportedOn}, the field a form
 * shows the error under (a range's end: {@code endsOn}; the field that needs the other:
 * {@code dueOnOrBefore}), never the getter's own name, which no client sends. GlobalExceptionHandler
 * reads the attribute; {@code ConfiguredMax} on a getter has the same one.
 */
@Documented
@Constraint(validatedBy = {})
@AssertTrue
@ReportAsSingleViolation
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CrossField {

    /** A sibling field of the getter's object, as the client sends it. */
    String reportedOn();

    String message();

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
