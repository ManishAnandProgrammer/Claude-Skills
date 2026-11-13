package com.example.app.user.domain;

/**
 * A user's role, sent in the access token's {@code roles} claim. Self-signup creates {@code USER};
 * further roles are added with the first feature that needs them.
 */
public enum Role {
    USER
}
