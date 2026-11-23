package com.example.app.common.security;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The caller holds at least one of {@link #value()}: one permission, or one per reach of a detail that several
 * reaches read, the policy then picking the reach (house-review AUTHZ-03, AUTHZ-05).
 * <p>
 * Spring Security 7's annotation scanners resolve the {@code {value}} template from this annotation's attribute, an
 * enum array becoming its names joined by commas ({@code ORDER_READ_OWN,ORDER_READ}), hence the split back into
 * {@code hasAnyAuthority}'s arguments. A misspelt permission fails at compile time, never on one endpoint at run time.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAnyAuthority('{value}'.split(','))")
public @interface Requires {

    Permission[] value();
}
