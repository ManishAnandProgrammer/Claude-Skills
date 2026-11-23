package com.example.app.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * As production runs the code under test: in tenancy's own tests each request's token alone names its tenant,
 * and a platform job runs outside any tenant.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface WithoutTestTenant {
}
