package com.example.app.user.api;

import java.util.UUID;

/**
 * The accounts, for the modules whose records name a person by account id (who asked for a todo): they ask
 * here, never the user repository. Without tenancy only: with it a person in a tenant is a member, asked
 * through {@code Members}, and bootstrap-copy.sh leaves this facade out.
 */
public interface Users {

    /** 404 {@code USER_NOT_FOUND} unless the account exists: for a write that names a person. */
    void requireUser(UUID userId);
}
