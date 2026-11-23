package com.example.app.common.security;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Set;

/**
 * The security chain turns these into the request's authorities, and nothing else becomes one: no {@code ROLE_}
 * authority, and the {@code FACTOR_BEARER} Spring Security adds itself grants nothing here.
 */
@FunctionalInterface
public interface TokenPermissions {

    Set<Permission> of(Jwt token);
}
