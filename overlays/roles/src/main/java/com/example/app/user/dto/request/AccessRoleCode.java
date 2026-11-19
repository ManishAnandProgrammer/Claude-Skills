package com.example.app.user.dto.request;

/**
 * A role code's format: upper-case letters, digits and underscores, starting with a letter, as a {@code Permission}
 * constant's is ({@code ck_roles_code} holds the same). A format, not a tunable; its length is
 * {@code app.limits.text.role.code}.
 */
final class AccessRoleCode {

    static final String PATTERN = "[A-Z][A-Z0-9_]*";
    static final String MESSAGE = "must be upper-case letters, digits and underscores, starting with a letter";

    private AccessRoleCode() {
    }
}
