package com.example.app.common.security;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// tenancy:begin
// /** On the platform's chain ({@code /platform/**}, active staff tokens only) it means every staff member. */
// tenancy:end
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("isAuthenticated()")
public @interface SignedIn {
}
