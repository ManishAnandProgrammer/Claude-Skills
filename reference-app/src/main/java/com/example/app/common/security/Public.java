package com.example.app.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A handler anyone may call, without a token: the security chain's public paths let it in, and
 * {@code EndpointContractIntegrationTest} checks that the two agree (a public path without this annotation, or
 * this annotation on a path that asks for a token). A marker only: no method check runs. Every handler declares
 * exactly one of this, {@link Requires} or {@link SignedIn} (ArchUnit).
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Public {
}
