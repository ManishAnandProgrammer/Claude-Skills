package com.example.app.common.security;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Set;

/**
 * The permissions a verified access token's bearer holds; the security chain turns them into the request's
 * authorities, and nothing else becomes one (no {@code ROLE_} authority; Spring Security adds its own
 * {@code FACTOR_BEARER}, how the caller signed in, which grants nothing here).
 *   <!-- roles:begin -->
 <!-- * With the roles piece it answers what the token's user holds now, read from the tables on every request -->
 <!-- * ({@code RolePermissions}, one statement): a change of their roles or grants reaches their next request, never a -->
 <!-- * token later. A database outage during that read is the request's 503 ({@code SecurityConfig}), never a 401. -->
 *   <!-- roles:else -->
 * The sign-in piece answers with the
 * token's role's permissions ({@code RolePermissions}), so a role change reaches a caller with their next
 * token; a project that signs users in elsewhere maps its identity provider's roles here. An unknown role
 * grants nothing.
 *   <!-- roles:end -->
 */
@FunctionalInterface
public interface TokenPermissions {

    Set<Permission> of(Jwt token);
}
