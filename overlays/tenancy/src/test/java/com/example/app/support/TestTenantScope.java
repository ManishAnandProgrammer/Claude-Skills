package com.example.app.support;

import com.example.app.common.tenancy.TenantScope;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** A test that calls a service or a job directly runs it in the test tenant, as a request with the tenant's token would. */
class TestTenantScope implements InvocationInterceptor {

    private final Supplier<UUID> testTenant;

    TestTenantScope(Supplier<UUID> testTenant) {
        this.testTenant = testTenant;
    }

    @Override
    public void interceptBeforeEachMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext,
                                          ExtensionContext extensionContext) throws Throwable {
        proceedInTestTenant(invocation, extensionContext);
    }

    @Override
    public void interceptTestMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext,
                                    ExtensionContext extensionContext) throws Throwable {
        proceedInTestTenant(invocation, extensionContext);
    }

    @Override
    public void interceptTestTemplateMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext,
                                            ExtensionContext extensionContext) throws Throwable {
        proceedInTestTenant(invocation, extensionContext);
    }

    @Override
    public void interceptAfterEachMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext,
                                         ExtensionContext extensionContext) throws Throwable {
        proceedInTestTenant(invocation, extensionContext);
    }

    private void proceedInTestTenant(Invocation<Void> invocation, ExtensionContext context) throws Throwable {
        if (context.getRequiredTestClass().isAnnotationPresent(WithoutTestTenant.class)) {
            invocation.proceed();
            return;
        }
        // TenantScope takes a Runnable; the test's own failure is rethrown as it was.
        AtomicReference<Throwable> failure = new AtomicReference<>();
        TenantScope.runAs(testTenant.get(), () -> {
            try {
                invocation.proceed();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        if (Objects.nonNull(failure.get())) {
            throw failure.get();
        }
    }
}
