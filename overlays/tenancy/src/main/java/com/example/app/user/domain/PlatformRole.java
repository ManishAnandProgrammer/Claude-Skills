package com.example.app.user.domain;

/** A platform staff member's role, sent in the platform token's {@code roles} claim. */
public enum PlatformRole {

    /** Manages tenants, and acts inside one through impersonation or an audited direct edit (platform_audit). */
    STAFF
}
