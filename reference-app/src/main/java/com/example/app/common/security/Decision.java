package com.example.app.common.security;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A policy's answer about one record: allowed, or refused with every unmet precondition, each the error code and
 * arguments the write would answer. The policy collects them in its order, most fundamental first, and a write
 * calls {@link #orThrow()}, which throws the first: the error contract answers one code. A record's allowed
 * actions show them all ({@link AllowedActionMapper}), so a person fixes everything at once. The policy never
 * picks an HTTP status (the error contract's mapper does). Not Spring's {@code AuthorizationDecision}, which
 * carries no reason.
 *
 * <p>Logged on the one authorization logger ({@code com.example.app.authorization}, off unless its level is set):
 * TRACE every decision, DEBUG a write it refused. Codes only, never a message or its arguments; the request's
 * context (user id, endpoint) is the log line's. Not an audit and not a metric.</p>
 */
@Slf4j(topic = "com.example.app.authorization")
public final class Decision {

    private static final Decision ALLOWED = new Decision(List.of());

    private final List<Refusal> refusals;

    private Decision(List<Refusal> refusals) {
        this.refusals = refusals;
    }

    public static Decision allowed() {
        log.trace("Decision: allowed");
        return ALLOWED;
    }

    /** One refusal: a reach rule (not yours, not found), which stands alone, or a rule with a single precondition. */
    public static Decision refused(ErrorCode code, Object... arguments) {
        log.trace("Decision: refused {}", code);
        return new Decision(List.of(Refusal.of(code, arguments)));
    }

    /** Every unmet precondition the policy found, in its order; allowed when there are none. */
    public static Decision of(List<Refusal> refusals) {
        if (refusals.isEmpty()) {
            return allowed();
        }
        Decision decision = new Decision(List.copyOf(refusals));
        log.trace("Decision: refused {}", decision.codes());
        return decision;
    }

    public boolean isAllowed() {
        return refusals.isEmpty();
    }

    /** Empty when allowed. */
    public List<Refusal> refusals() {
        return Collections.unmodifiableList(refusals);
    }

    /** Throws the first refusal; nothing when allowed. */
    public void orThrow() {
        if (!isAllowed()) {
            log.debug("Write refused: {}", codes());
            Refusal first = refusals.getFirst();
            throw AppException.of(first.code(), first.arguments().toArray());
        }
    }

    /** The refusals' codes, in order: what the log may show of them. */
    public List<ErrorCode> codes() {
        return refusals.stream().map(Refusal::code).toList();
    }

    /** One unmet precondition: the error code the write answers and the arguments of its message. */
    public record Refusal(ErrorCode code, List<Object> arguments) {

        public Refusal {
            Objects.requireNonNull(code, "code");
            // Not List.copyOf: an argument may be null, as AppException.of takes it.
            arguments = Collections.unmodifiableList(Arrays.asList(arguments.toArray()));
        }

        public static Refusal of(ErrorCode code, Object... arguments) {
            return new Refusal(code, Arrays.asList(arguments));
        }
    }
}
