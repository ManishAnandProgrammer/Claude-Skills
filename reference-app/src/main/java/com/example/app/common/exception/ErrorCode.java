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
 * can return it, and a negative test asserting it.</p>
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
    AUTH_INVALID_CREDENTIALS,
    AUTH_INVALID_REFRESH_TOKEN,

    // ── TodoList ─────────────────────────────────────────────────────────
    TODO_LIST_NOT_FOUND,
    TODO_LIST_NAME_TAKEN,
    TODO_LIST_IN_USE,

    // ── Todo ─────────────────────────────────────────────────────────────
    TODO_NOT_FOUND
}
