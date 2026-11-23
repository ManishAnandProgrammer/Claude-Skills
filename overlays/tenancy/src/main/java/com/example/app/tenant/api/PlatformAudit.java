package com.example.app.tenant.api;

// session-version:begin
import java.time.Instant;
import java.util.List;
// session-version:end
import java.util.UUID;

public interface PlatformAudit {

    /** In its own transaction: an attempt is recorded even when the action is then refused. */
    void record(UUID tenantId, String action, String detail);

    /** As {@link #record}, naming the member acted as. */
    void recordImpersonation(UUID tenantId, UUID userId, String detail);
    // session-version:begin

    /** Each member the staff member started an impersonation of since then, once, the refused attempts included. */
    List<ImpersonatedMember> impersonatedBy(UUID staffId, Instant since);
    // session-version:end
}
