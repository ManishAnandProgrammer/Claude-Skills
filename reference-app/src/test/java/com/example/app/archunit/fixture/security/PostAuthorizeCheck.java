package com.example.app.archunit.fixture.security;

import org.springframework.security.access.prepost.PostAuthorize;

@PostAuthorize("isAuthenticated()")
public class PostAuthorizeCheck {

    @PostAuthorize("isAuthenticated()")
    public Object checkedAfterwards() {
        return null;
    }
}
