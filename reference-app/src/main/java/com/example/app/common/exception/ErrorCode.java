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

    // ── Security ─────────────────────────────────────────────────────────
    AUTHENTICATION_REQUIRED,
    ACCESS_DENIED,
    /** A dropdown's {@code for} names a permission whose reach that dropdown doesn't list (every record, on one of own records). */
    DROPDOWN_REACH_UNSUPPORTED,
    RATE_LIMITED,
    /** A cross-origin request whose origin, or a preflight whose method or headers, the CORS settings don't allow. */
    CORS_REQUEST_REFUSED,

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
    /** A password change whose current password doesn't match: counted as a failed login of the account. */
    USER_PASSWORD_INCORRECT,
    // tenancy:begin
    // tenancy:else
    /** Nobody suspends or reactivates their own account (house-review SEC-02): a rule that never depends on state, so 403. */
    USER_OWN_STATUS_CHANGE,
    // tenancy:end
    AUTH_INVALID_CREDENTIALS,
    AUTH_INVALID_REFRESH_TOKEN,
    /** A suspended account gets no token from any path (after the password matched, at login). */
    AUTH_ACCOUNT_SUSPENDED,
    // session-version:begin
    /** The access token was issued before the account's access last changed: refresh the tokens. */
    SESSION_VERSION_STALE,
    // session-version:end
    // tenancy:begin
    //
    // // ── Tenancy ──────────────────────────────────────────────────────────
    // /** Tokens are issued only for an ACTIVE tenant; the response names the status ({@code tenantStatus}). */
    // TENANT_NOT_ACTIVE,
    // /** The account belongs to no tenant yet: there is nothing to sign in to. */
    // TENANT_MEMBERSHIP_REQUIRED,
    // /** Switch-tenant to a tenant the caller isn't a member of (or that doesn't exist). */
    // MEMBERSHIP_NOT_FOUND,
    // TENANT_NOT_FOUND,
    // /** A tenant's owner must already have an account (invitations come later). */
    // TENANT_OWNER_NOT_FOUND,
    // TENANT_STATUS_CHANGE_INVALID,
    // /** The application's support-access policy refused an impersonation. */
    // SUPPORT_ACCESS_DENIED,
    // /** A tenant ADMIN's member management: the user isn't a member of the caller's tenant. */
    // TENANT_MEMBER_NOT_FOUND,
    // /** Members are existing accounts (invitations come later). */
    // TENANT_MEMBER_ACCOUNT_NOT_FOUND,
    // TENANT_MEMBER_EXISTS,
    // /** Every tenant keeps an ADMIN with an ACTIVE account: the last one can't be demoted, removed or suspended. */
    // TENANT_MEMBER_LAST_ADMIN,
    // /** Nobody changes or removes their own membership (house-review SEC-02): a rule that never depends on state, so 403. */
    // TENANT_MEMBER_OWN_CHANGE,
    // tenancy:end
    // metadata:begin

    // ── Configuration metadata ───────────────────────────────────────────
    /** No key of that name is declared in {@code MetadataKeys}. */
    METADATA_KEY_UNKNOWN,
    /** The key is declared, but its value was never created (its seeding didn't run). */
    METADATA_NOT_FOUND,
    METADATA_KEY_TAKEN,
    // metadata:end
    // media:begin

    // ── Media uploads ────────────────────────────────────────────────────
    /** The purpose is declared in {@code UploadPurpose}, but its policy row was never seeded. */
    UPLOAD_POLICY_NOT_FOUND,
    UPLOAD_SIZE_OUT_OF_RANGE,
    UPLOAD_TYPE_NOT_ALLOWED,
    /** Too many files in one request, or a record's files outside the purpose's range after a change. */
    UPLOAD_FILE_COUNT_OUT_OF_RANGE,
    /** Unknown, someone else's, another purpose's, already used or expired: deliberately one code. */
    UPLOAD_NOT_AVAILABLE,
    /** The object store (or its credentials source) didn't answer. */
    MEDIA_STORAGE_UNAVAILABLE,
    // media:end
    // example:begin (the golden todo and planning modules; bootstrap-copy.sh leaves them out of real projects)

    // ── TodoList ─────────────────────────────────────────────────────────
    TODO_LIST_NOT_FOUND,
    TODO_LIST_NAME_TAKEN,
    TODO_LIST_IN_USE,
    /** Deleted between the write's read of it and the insert or update of a todo into it (a foreign key, ERR-06: no id). */
    TODO_LIST_GONE,

    // ── Todo ─────────────────────────────────────────────────────────────
    TODO_NOT_FOUND,
    /** A write of a todo by its requester, who may read it (so 403, not 404) but not change it: the todo's id. */
    TODO_NOT_YOURS,
    TODO_IN_USE,
    /** Deleted between the write's read of it and the insert of a row onto it (a time entry, a plan, a tag link). */
    TODO_GONE,

    // ── TimeEntry ────────────────────────────────────────────────────────
    TIME_ENTRY_NOT_FOUND,

    // ── Tag ──────────────────────────────────────────────────────────────
    TAG_NOT_FOUND,
    TAG_NAME_TAKEN,
    /** Deleted between the write's read of it and the insert of its link to a todo. */
    TAG_GONE,
    /** A merge that names one tag as both the source and the target: the tag's id. */
    TAG_MERGE_INTO_ITSELF,
    // media:begin

    // ── Todo attachments ─────────────────────────────────────────────────
    /** A removeAttachmentIds entry that is not one of this todo's files. */
    TODO_ATTACHMENT_NOT_FOUND,
    // media:end

    // ── Plan (the planning module) ───────────────────────────────────────
    PLAN_NOT_FOUND,
    /** Answered from the exclusion constraint ex_plans_owner_id_dates: no arguments. */
    PLAN_OVERLAPS,
    /** A plan for a todo that is done: plans are for open todos. */
    PLAN_TODO_DONE,
    /** A move PlanStatus.canBecome doesn't allow: the plan's status and the target. */
    PLAN_STATUS_CHANGE_INVALID,
    /** A done day before the plan's first: the day and the first day. */
    PLAN_DONE_ON_BEFORE_STARTS_ON,
    /** An edit of a DONE plan, which is final: the plan's id. */
    PLAN_DONE,
    /** An edit of a CANCELLED plan, which is final: the plan's id. */
    PLAN_CANCELLED,
    // example:end
}
