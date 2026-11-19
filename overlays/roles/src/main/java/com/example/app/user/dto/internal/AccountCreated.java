package com.example.app.user.dto.internal;

import java.util.UUID;

/**
 * An account was just created (the roles piece): published inside the creating transaction, so its first role, the
 * built-in {@code USER}, is written in the same one ({@code PersonAccessService}).
 */
public record AccountCreated(UUID userId) {
}
