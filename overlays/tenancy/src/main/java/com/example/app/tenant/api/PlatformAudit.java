package com.example.app.tenant.api;

import java.util.UUID;

/**
 * The platform's append-only audit trail: every impersonation and every direct cross-tenant edit by
 * platform staff, always on. The signed-in staff member is the actor.
 */
public interface PlatformAudit {

    /**
     * One row, in its own transaction: an attempt is recorded even when the action is then refused.
     *
     * @param action what was done, an UPPER_SNAKE_CASE name ({@code IMPERSONATION}, or a direct edit such as {@code SETTINGS_UPDATE})
     * @param detail what it was done to, and why when given (a key, a user id, a ticket reference)
     */
    void record(UUID tenantId, String action, String detail);
}
