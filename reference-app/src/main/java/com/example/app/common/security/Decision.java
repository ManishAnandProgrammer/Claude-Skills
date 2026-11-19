package com.example.app.common.security;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;

import java.util.List;
import java.util.Objects;

/**
 * A policy's answer about one record: allowed, or refused with the error code and arguments the caller gets.
 * The policy never picks an HTTP status (the error contract's mapper does), and a write calls {@link #orThrow()}.
 * Not Spring's {@code AuthorizationDecision}, which carries no reason.
 */
public final class Decision {

    private static final Decision ALLOWED = new Decision(null, List.of());

    private final ErrorCode refusal;
    private final List<Object> arguments;

    private Decision(ErrorCode refusal, List<Object> arguments) {
        this.refusal = refusal;
        this.arguments = arguments;
    }

    public static Decision allowed() {
        return ALLOWED;
    }

    public static Decision refused(ErrorCode refusal, Object... arguments) {
        return new Decision(Objects.requireNonNull(refusal, "refusal"), List.of(arguments));
    }

    public boolean isAllowed() {
        return Objects.isNull(refusal);
    }

    /** Throws the refusal; nothing when allowed. */
    public void orThrow() {
        if (!isAllowed()) {
            throw AppException.of(refusal, arguments.toArray());
        }
    }
}
