package com.example.app.common.security;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The role check of admin endpoints, written once: a misspelt role in a {@code @PreAuthorize} string
 * fails only at run time, a misspelt annotation at compile time. The role is {@code Role.ADMIN} in the
 * user module (or the identity provider's {@code ADMIN} role).
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasRole('ADMIN')")
public @interface AdminOnly {
}
