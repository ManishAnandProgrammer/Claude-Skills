package com.example.app.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Pattern;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = {})
@Pattern(regexp = "\\p{ASCII}*")
@ReportAsSingleViolation
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface AsciiOnly {

    String message() default "must contain only ASCII characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
