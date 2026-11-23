package com.example.app.common.security;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Logged by codes only, never a message or its arguments. */
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

    public static Decision refused(ErrorCode code, Object... arguments) {
        log.trace("Decision: refused {}", code);
        return new Decision(List.of(Refusal.of(code, arguments)));
    }

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

    public List<Refusal> refusals() {
        return Collections.unmodifiableList(refusals);
    }

    public void orThrow() {
        if (!isAllowed()) {
            log.debug("Write refused: {}", codes());
            Refusal first = refusals.getFirst();
            throw AppException.of(first.code(), first.arguments().toArray());
        }
    }

    public List<ErrorCode> codes() {
        return refusals.stream().map(Refusal::code).toList();
    }

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
