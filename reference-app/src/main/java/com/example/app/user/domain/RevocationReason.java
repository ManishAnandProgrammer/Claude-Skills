package com.example.app.user.domain;

public enum RevocationReason {
    ROTATED,
    LOGOUT,
    /** A rotated token was presented again after the grace window: every session was revoked. */
    REUSE,
    /** The password changed: every session of the account was revoked; the changing device got new tokens. */
    PASSWORD_CHANGED,
    /** The account was suspended: every session was revoked, and reactivating it revives none. */
    SUSPENDED
}
