package com.example.app.common.exception;

/**
 * Protocol-agnostic error identities. Each constant is one well-defined failure mode.
 *
 * <p>The HTTP status lives in {@link ErrorCodeHttpStatusMapper}, the only place an
 * {@code ErrorCode} meets an {@code HttpStatus}. The message is resolved at the REST boundary from
 * {@code messages/message_<locale>.properties}, keyed by {@link #name()}.</p>
 *
 * <p>A new constant needs, in the same change: a mapper entry, a message in every locale file
 * ({@code ErrorCodeContractTest} fails otherwise), an {@code @ApiResponse} on every endpoint that
 * can return it (or, for one every endpoint can return, an {@code OpenApiCustomizer} that adds it),
 * and a negative test asserting it.</p>
 */
public enum ErrorCode {

    // ── Request ──────────────────────────────────────────────────────────
    VALIDATION_FAILED,
    REQUEST_UNREADABLE,
    REQUEST_PARAMETER_MISSING,
    REQUEST_PARAMETER_INVALID,
    REQUEST_METHOD_NOT_ALLOWED,
    REQUEST_MEDIA_TYPE_UNSUPPORTED,
    REQUEST_NOT_ACCEPTABLE,
    /** Missing, malformed or not supported, whatever carries it (path, header); a client mismatch, not a form error. */
    API_VERSION_UNSUPPORTED,
    /** Any other refusal of the request's form that has no more specific code. */
    REQUEST_INVALID,
    RESOURCE_NOT_FOUND,
    ILLEGAL_ARGUMENT,

    // ── Security ─────────────────────────────────────────────────────────
    AUTHENTICATION_REQUIRED,
    ACCESS_DENIED,
    RATE_LIMITED,

    // ── Data ─────────────────────────────────────────────────────────────
    DUPLICATE_RECORD,
    OPTIMISTIC_LOCK_CONFLICT,
    DATA_INTEGRITY_ERROR,

    // ── Availability ─────────────────────────────────────────────────────
    DATABASE_CONTENTION,
    QUERY_TIMEOUT,
    DATABASE_UNAVAILABLE,
    SERVER_BUSY,
    UNEXPECTED_ERROR,

    // ── User and sign-in ─────────────────────────────────────────────────
    USER_NOT_FOUND,
    USER_EMAIL_TAKEN,
    USER_PASSWORD_BREACHED,
    AUTH_INVALID_CREDENTIALS,
    AUTH_INVALID_REFRESH_TOKEN,
    // example:begin (the golden todo module; bootstrap-copy.sh leaves it out of real projects)

    // ── TodoList ─────────────────────────────────────────────────────────
    TODO_LIST_NOT_FOUND,
    TODO_LIST_NAME_TAKEN,
    TODO_LIST_IN_USE,

    // ── Todo ─────────────────────────────────────────────────────────────
    TODO_NOT_FOUND,
    TODO_IN_USE,

    // ── TimeEntry ────────────────────────────────────────────────────────
    TIME_ENTRY_NOT_FOUND,

    // ── Tag ──────────────────────────────────────────────────────────────
    TAG_NOT_FOUND,
    TAG_NAME_TAKEN,
    // example:end
}
