package com.example.app.tenant.api;

import java.util.Set;

/** Stored as text with a check constraint, so a status comparison stays a leakproof operator in a policy. */
public enum TenantStatus {

    PENDING,
    ACTIVE,
    SUSPENDED,
    CLOSED,
    REJECTED;

    public boolean canBecome(TenantStatus target) {
        return switch (this) {
            case PENDING -> Set.of(ACTIVE, REJECTED).contains(target);
            case ACTIVE -> target == SUSPENDED;
            case SUSPENDED -> Set.of(ACTIVE, CLOSED).contains(target);
            case CLOSED, REJECTED -> false;
        };
    }
}
