package com.example.app.common.security;

import java.util.UUID;

/**
 * Whether a platform staff member may still act (the tenancy piece), for {@link ActiveStaffFilter}:
 * implemented by the user module, which owns the staff rows, so common knows no feature.
 */
public interface StaffAccounts {

    /** False for a deactivated staff member, or one whose row is gone. */
    boolean isActive(UUID staffId);
}
