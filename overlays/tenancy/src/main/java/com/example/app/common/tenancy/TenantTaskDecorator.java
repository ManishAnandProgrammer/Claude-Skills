package com.example.app.common.tenancy;

import org.springframework.core.task.TaskDecorator;

import java.util.UUID;

/** The security context, where a request's tenant comes from, stays on the request's thread. */
public class TenantTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable task) {
        UUID tenantId = TenantContext.current();
        return () -> TenantScope.runAs(tenantId, task);
    }
}
