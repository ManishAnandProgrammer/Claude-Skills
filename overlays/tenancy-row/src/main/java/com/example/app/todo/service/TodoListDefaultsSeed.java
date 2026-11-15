package com.example.app.todo.service;

import com.example.app.metadata.api.Metadata;
import com.example.app.metadata.api.MetadataKeys;
import com.example.app.metadata.api.TodoListSettings;
import com.example.app.tenant.api.TenantProvisioned;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The fixed key TODO_LIST_DEFAULTS in every new tenant (row mode). A changeset can't seed a tenant's
 * rows: there is no tenant when it runs, and row-level security refuses the owner too. So the module
 * that starts its lists from the key seeds it when a tenant is provisioned, under its scope and in its
 * activating transaction, with the value changeset 0014 gives the tenants that already existed. Only
 * when missing: 0014 also reached the tenants still PENDING when it ran, and their approval must keep
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
