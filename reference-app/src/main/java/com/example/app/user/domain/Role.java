package com.example.app.user.domain;

/**
 * A user's role, sent in the access token's {@code roles} claim.
 *   <!-- tenancy:begin -->
 * With tenancy it is the person's role in one tenant, their membership's: the account's own
 * {@code users.role} is not what a tenant endpoint sees. A tenant's owner becomes its first ADMIN; after
 * that a tenant ADMIN adds members with a role ({@code POST /tenant-members}) and changes it
 * ({@code PUT /tenant-members/{userId}}). A change reaches the member's next sign-in or refresh at the
 * latest.
 *   <!-- session-version:begin -->
 * It also raises the membership's session version, so the member's next request with an older token is
 * refused (401 {@code SESSION_VERSION_STALE}) and the refresh that follows brings the new role.
 *   <!-- session-version:end -->
 *   <!-- tenancy:else -->
 * Self-signup creates {@code USER}; further roles are added with the first feature that needs them.
 *   <!-- tenancy:end -->
 * Each role's javadoc names what it writes (on every role, when every role writes a resource); check it
 * is still true before adding to it.
 */
public enum Role {
    USER,
    // admin:begin (with the first piece that has admin endpoints: metadata or media)
    // tenancy:begin
    // /** Everything a USER works, plus the tenant's members (who belongs, with which role) and its own settings. */
    // tenancy:else
    /**
     * Changes application-wide settings: configuration metadata, upload policies. No endpoint grants
     * it: an operator sets it in the database ({@code UPDATE users SET role = 'ADMIN'}; docs/runbook.md,
     * Operator tasks), and it reaches the user's next sign-in or refresh at the latest.
     *   <!-- session-version:begin -->
     * The runbook's statement also raises the account's session version, so the user's next request
     * with an older token is refused (401 {@code SESSION_VERSION_STALE}) and the refresh that follows
     * brings the new role.
     *   <!-- session-version:end -->
     */
    // tenancy:end
    ADMIN
    // admin:end
}
