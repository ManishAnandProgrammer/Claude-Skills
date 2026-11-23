package com.example.app.user.dto.request;

/** {@code ck_roles_code} checks the same format: a format, not a tunable. */
final class AccessRoleCode {

    static final String PATTERN = "[A-Z][A-Z0-9_]*";
    static final String MESSAGE = "must be upper-case letters, digits and underscores, starting with a letter";

    private AccessRoleCode() {
    }
}
