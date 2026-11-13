package com.example.app.common.scheduling;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a {@code @Scheduled} method is safe when every instance runs it at once, and why
 * (idempotent, local to the instance). Several instances run in production, so every scheduled
 * method carries this or a distributed lock; an ArchUnit rule ({@code ConventionArchTest}) fails the
 * build otherwise.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MultiInstanceSafe {

    /** Why running on every instance at the same time is harmless. */
    String value();
}
