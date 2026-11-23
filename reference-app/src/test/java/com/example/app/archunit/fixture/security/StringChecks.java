package com.example.app.archunit.fixture.security;

import org.springframework.security.access.prepost.PreAuthorize;

@PreAuthorize("isAuthenticated()")
public class StringChecks {

    @PreAuthorize("hasAuthority('TODO_READ_OWN')")
    public void namesAPermissionInAString() {
    }
}
