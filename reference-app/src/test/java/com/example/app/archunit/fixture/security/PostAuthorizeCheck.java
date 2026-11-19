package com.example.app.archunit.fixture.security;

import org.springframework.security.access.prepost.PostAuthorize;

/** A @PostAuthorize string outside common.security, alone, for methodSecurityIsDeclaredOnce and its class twin. */
@PostAuthorize("isAuthenticated()")
public class PostAuthorizeCheck {

    @PostAuthorize("isAuthenticated()")
    public Object checkedAfterwards() {
        return null;
    }
}
