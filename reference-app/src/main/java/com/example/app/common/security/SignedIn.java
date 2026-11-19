package com.example.app.common.security;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A handler every signed-in caller may use, whatever they hold: their own account ({@code /users/me}), or a
 * service that checks a permission the request names (a dropdown's {@code for}, an upload's purpose). Every
 * handler declares exactly one of this, {@link Requires} or {@link Public} (ArchUnit).
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("isAuthenticated()")
public @interface SignedIn {
}
