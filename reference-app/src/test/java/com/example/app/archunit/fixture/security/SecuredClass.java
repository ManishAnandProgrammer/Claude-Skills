package com.example.app.archunit.fixture.security;

import org.springframework.security.access.annotation.Secured;

/** A role check on a class, for noRoleChecks. */
@Secured("ROLE_ADMIN")
public class SecuredClass {
}
