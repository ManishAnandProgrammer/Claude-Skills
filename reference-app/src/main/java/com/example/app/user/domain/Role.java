package com.example.app.user.domain;

/**
 * A user's role, sent in the access token's {@code roles} claim. Self-signup creates {@code USER};
 * further roles are added with the first feature that needs them.
 */
public enum Role {
    USER,
    // metadata:begin
    /**
     * Reads and changes configuration metadata. No endpoint grants it: an operator sets it in the
     * database ({@code UPDATE users SET role = 'ADMIN'}), and it takes effect at the user's next sign-in.
     */
    ADMIN
    // metadata:end
}
