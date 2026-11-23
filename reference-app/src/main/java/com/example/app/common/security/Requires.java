package com.example.app.common.security;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Spring Security 7's annotation scanners resolve the {@code {value}} template from this annotation's attribute
 * (an enum constant becomes its name), so a misspelt permission fails at compile time, never on one endpoint at
 * run time.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAuthority('{value}')")
public @interface Requires {

    Permission value();
}
