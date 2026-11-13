package com.example.app.common.exception;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What a constraint the database enforced means to a client: the domain code a module declared for
 * it ({@link ConstraintErrorCodes}), else {@code DUPLICATE_RECORD} for any unique violation. Anything
 * else is empty: a bug, answered 500.
 */
@Component
class ConstraintViolationCodes {

    private static final String UNIQUE_VIOLATION = "23505";

    private final Map<String, ErrorCode> byConstraintName;

    ConstraintViolationCodes(List<ConstraintErrorCodes> contributors) {
        Map<String, ErrorCode> merged = new HashMap<>();
        for (ConstraintErrorCodes contributor : contributors) {
            for (Map.Entry<String, ErrorCode> entry : contributor.byConstraintName().entrySet()) {
                ErrorCode previous = merged.putIfAbsent(entry.getKey(), entry.getValue());
                if (Objects.nonNull(previous)) {
                    throw new IllegalStateException("Constraint %s is claimed twice: %s and %s"
                            .formatted(entry.getKey(), previous, entry.getValue()));
                }
            }
        }
        this.byConstraintName = Map.copyOf(merged);
    }

    Optional<ErrorCode> codeFor(DataIntegrityViolationException exception) {
        return constraintViolation(exception).flatMap(this::codeFor);
    }

    private Optional<ErrorCode> codeFor(ConstraintViolationException violation) {
        Optional<ErrorCode> domainCode = Optional.ofNullable(violation.getConstraintName()).map(byConstraintName::get);
        if (domainCode.isPresent() || !UNIQUE_VIOLATION.equals(violation.getSQLState())) {
            return domainCode;
        }
        return Optional.of(ErrorCode.DUPLICATE_RECORD);
    }

    private static Optional<ConstraintViolationException> constraintViolation(Throwable failure) {
        for (Throwable cause = failure; Objects.nonNull(cause); cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return Optional.of(violation);
            }
        }
        return Optional.empty();
    }
}
