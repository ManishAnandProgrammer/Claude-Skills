package com.example.app.user.domain;

// roles:begin
/** A platform staff member's role; no token carries it (the roles piece puts nothing about access in a token). */
// roles:else
// /** A platform staff member's role, sent in the platform token's {@code roles} claim. */
// roles:end
public enum PlatformRole {

    /** Manages tenants, and acts inside one through impersonation or an audited direct edit (platform_audit). */
    STAFF
}
