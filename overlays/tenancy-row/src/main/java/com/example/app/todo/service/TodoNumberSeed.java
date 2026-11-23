package com.example.app.todo.service;

import com.example.app.metadata.api.Counters;
import com.example.app.tenant.api.TenantProvisioned;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Only when missing: changeset 0026 gave every tenant that existed when it ran its counter, a PENDING one too, and
 * that tenant's approval must keep it.
 */
@Component
@RequiredArgsConstructor
class TodoNumberSeed {

    private final Counters counters;

    @EventListener
    void seed(TenantProvisioned provisioned) {
        counters.createIfAbsent(TodoService.NUMBER_COUNTER, 0);
    }
}
