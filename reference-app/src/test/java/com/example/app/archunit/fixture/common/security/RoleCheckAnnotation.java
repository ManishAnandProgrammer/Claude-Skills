package com.example.app.archunit.fixture.common.security;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** A role check declared once, in a common.security package: the string is allowed there, the role nowhere. */
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasRole('ADMIN')")
public @interface RoleCheckAnnotation {
}
