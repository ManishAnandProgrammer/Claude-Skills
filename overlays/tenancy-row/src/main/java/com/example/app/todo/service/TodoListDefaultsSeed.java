package com.example.app.todo.service;

import com.example.app.metadata.api.Metadata;
import com.example.app.metadata.api.MetadataKeys;
import com.example.app.metadata.api.TodoListSettings;
import com.example.app.tenant.api.TenantProvisioned;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Only when missing: changeset 0014 also reached the tenants still PENDING when it ran, and their approval must keep
 * that row instead of failing on it (a tenant that can never be activated).
 */
@Component
@RequiredArgsConstructor
class TodoListDefaultsSeed {

    private final Metadata metadata;

    @EventListener
    void seed(TenantProvisioned provisioned) {
        metadata.createIfAbsent(MetadataKeys.TODO_LIST_DEFAULTS, TodoListSettings.defaults());
    }
}
