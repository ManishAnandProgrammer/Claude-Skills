package com.example.app.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * At most 72 bytes in UTF-8: bcrypt ignores every byte after the 72nd, so two long passwords with the same start
 * would match each other.
 */
@Documented
@Constraint(validatedBy = {})
@NotBlank
@Size(min = 8, message = "must be at least 8 characters")
@MaxUtf8Bytes(72)
@Pattern(regexp = "^(?=.*\\p{Lu})(?=.*\\p{Ll})(?=.*\\d)(?=.*[^\\p{L}\\d]).*$",
        message = "must contain an upper-case letter, a lower-case letter, a digit and another character")
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface PasswordPolicy {

    /** Never reported: each rule above reports its own violation. */
    String message() default "must follow the password policy";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
