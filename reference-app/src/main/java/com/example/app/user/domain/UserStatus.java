package com.example.app.user.domain;

/** Whether an account may sign in. Any status but ACTIVE gets no token (SEC-06: an unknown one is a refusal). */
public enum UserStatus {
    ACTIVE,
    /** An ADMIN's (with tenancy, platform staff's) decision: every session ended, no new one until reactivated. */
    SUSPENDED
}
