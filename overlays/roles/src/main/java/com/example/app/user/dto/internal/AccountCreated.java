package com.example.app.user.dto.internal;

import java.util.UUID;

/** Published inside the creating transaction, so the account's first role is written in the same one. */
public record AccountCreated(UUID userId) {
}
