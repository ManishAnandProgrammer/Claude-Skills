package com.example.app.archunit.fixture.security;

import org.springframework.security.access.prepost.PreAuthorize;

/** Method security written as strings outside common.security, for methodSecurityIsDeclaredOnce and its class twin. */
@PreAuthorize("isAuthenticated()")
public class StringChecks {

    @PreAuthorize("hasAuthority('TODO_READ_OWN')")
    public void namesAPermissionInAString() {
    }
}
