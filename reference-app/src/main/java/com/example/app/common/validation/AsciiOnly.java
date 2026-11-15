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

/**
 * Only ASCII characters: the house email shape ({@code @NotBlank @Email @MaxLength @AsciiOnly}), so a stored
 * address has one spelling ({@link Emails}). An empty value passes: {@code @NotBlank} reports it alone.
 */
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
