package com.example.app.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A test class whose tests run without the test tenant's scope ({@link TestTenantScope}), as production
 * runs that code: tests of tenancy itself (isolation, the planes, impersonation), where each request's
 * token alone names its tenant, and tests of a platform-wide job, which runs outside any tenant. Every
 * other test class runs inside the test tenant.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface WithoutTestTenant {
}
