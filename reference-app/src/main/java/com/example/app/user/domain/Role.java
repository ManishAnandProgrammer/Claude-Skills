package com.example.app.user.domain;

/**
 * A user's role, sent in the access token's {@code roles} claim. Self-signup creates {@code USER};
 * further roles are added with the first feature that needs them.
 */
public enum Role {
    USER,
    // admin:begin (with the first piece that has admin endpoints: metadata or media)
    /**
     * Changes application-wide settings: configuration metadata, upload policies. No endpoint grants
     * it: an operator sets it in the database ({@code UPDATE users SET role = 'ADMIN'}; docs/runbook.md,
     * Operator tasks), and it takes effect at the user's next sign-in. A project names here everything
     * its ADMIN writes, from the first generated write that takes the role.
     */
    ADMIN
    // admin:end
}
