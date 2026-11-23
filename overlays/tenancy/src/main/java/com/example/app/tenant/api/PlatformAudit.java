package com.example.app.tenant.api;

import java.util.UUID;

public interface PlatformAudit {

    /** In its own transaction: an attempt is recorded even when the action is then refused. */
    void record(UUID tenantId, String action, String detail);
}
