package com.example.app.todo.service;

import com.example.app.metadata.api.Counters;
import com.example.app.tenant.api.TenantProvisioned;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The todos' display-number counter in every new tenant (row mode), at 0: the tenant's first todo is #1.
 * Created only when missing: changeset 0026 gave every tenant that existed when it ran its counter, a
 * PENDING one too, and that tenant's approval must keep it. The create calls createIfAbsent as well, for a
 * tenant activated by an instance without this listener (a rolling deploy).
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
