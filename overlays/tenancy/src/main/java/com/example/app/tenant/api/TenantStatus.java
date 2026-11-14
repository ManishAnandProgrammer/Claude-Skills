package com.example.app.tenant.api;

import java.util.Set;

/**
 * A tenant's lifecycle: {@code PENDING -> ACTIVE <-> SUSPENDED -> CLOSED}, and {@code PENDING -> REJECTED}.
 * Only an ACTIVE tenant's members get tokens. Stored as text with a check constraint, so a status
 * comparison stays a leakproof operator in a policy.
 */
public enum TenantStatus {

    /** Created, not usable yet: provisioning, or awaiting staff approval. */
    PENDING,
    ACTIVE,
    /** Temporarily refused (staff, later billing); reactivated or closed. */
    SUSPENDED,
    /** For good: nobody signs in to it again. Exporting and deleting its data is an owner's step; no job does it. */
    CLOSED,
    /** A registration staff turned down. */
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
