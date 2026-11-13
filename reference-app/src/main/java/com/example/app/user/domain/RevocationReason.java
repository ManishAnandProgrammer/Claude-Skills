package com.example.app.user.domain;

public enum RevocationReason {
    ROTATED,
    LOGOUT,
    /** A rotated token was presented again after the grace window: every session was revoked. */
    REUSE
}
