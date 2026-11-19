package com.example.app.archunit.fixture.security;

import jakarta.annotation.security.RolesAllowed;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authorization.AuthorityAuthorizationManager;

/** Every way of checking a role, for noRoleChecks and noRoleChecksOnMethods. */
public class RoleChecks {

    private RoleHierarchy hierarchy;

    @PreAuthorize("hasRole('ADMIN')")
    public void roleInAnExpression() {
    }

    @Secured("ROLE_ADMIN")
    public void securedRole() {
    }

    @RolesAllowed("ADMIN")
    public void rolesAllowed() {
    }

    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public void roleAsAnAuthority() {
    }

    public Object roleInCode() {
        return AuthorityAuthorizationManager.hasRole("ADMIN");
    }

    public RoleHierarchy hierarchy() {
        return hierarchy;
    }
}
