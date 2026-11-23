package com.example.app.archunit.fixture.security;

import org.springframework.security.access.annotation.Secured;

@Secured("ROLE_ADMIN")
public class SecuredClass {
}
