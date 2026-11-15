package com.example.app.user.api;

import java.util.UUID;

/**
 * A member of the current tenant as another module sees it. A module contract, never a response body:
 * the consuming module maps it into a response record of its own. The role stays in the user module
 * ({@code Role} is a domain type): a module that needs a rule on it asks for a method here.
 */
public record MemberView(UUID userId, String email, String fullName) {
}
