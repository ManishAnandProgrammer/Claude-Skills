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

/** The violation names {@link #reportedOn}, never the getter's own name, which no client sends. */
@Documented
@Constraint(validatedBy = {})
@AssertTrue
@ReportAsSingleViolation
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CrossField {

    String reportedOn();

    String message();

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
