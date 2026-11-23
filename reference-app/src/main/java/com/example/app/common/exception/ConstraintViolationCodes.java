package com.example.app.common.exception;

import org.hibernate.JDBCException;
import org.hibernate.exception.ConstraintViolationException;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Component
class ConstraintViolationCodes {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    private final Map<String, ErrorCode> byConstraintName;
    private final Map<String, ErrorCode> byForeignKeyOnWrite;

    // Hand-written: the modules' claims are merged once into immutable maps, and a constraint two modules claim
    // stops the startup.
    ConstraintViolationCodes(List<ConstraintErrorCodes> contributors) {
        Map<String, ErrorCode> merged = new HashMap<>();
        Map<String, ErrorCode> mergedOnWrite = new HashMap<>();
        for (ConstraintErrorCodes contributor : contributors) {
            claim(merged, contributor.byConstraintName());
            claim(mergedOnWrite, contributor.byForeignKeyOnWrite());
        }
        this.byConstraintName = Map.copyOf(merged);
        this.byForeignKeyOnWrite = Map.copyOf(mergedOnWrite);
    }

    private static void claim(Map<String, ErrorCode> merged, Map<String, ErrorCode> declared) {
        for (Map.Entry<String, ErrorCode> entry : declared.entrySet()) {
            ErrorCode previous = merged.putIfAbsent(entry.getKey(), entry.getValue());
            if (Objects.nonNull(previous)) {
                throw new IllegalStateException("Constraint %s is claimed twice: %s and %s"
                        .formatted(entry.getKey(), previous, entry.getValue()));
            }
        }
    }

    Optional<ErrorCode> codeFor(DataIntegrityViolationException exception) {
        return constraintViolation(exception).flatMap(this::codeFor);
    }

    private Optional<ErrorCode> codeFor(ConstraintViolationException violation) {
        if (FOREIGN_KEY_VIOLATION.equals(violation.getSQLState()) && isInsertOrUpdate(violation)) {
            return constraintName(violation).map(byForeignKeyOnWrite::get);
        }
        Optional<ErrorCode> domainCode = constraintName(violation).map(byConstraintName::get);
        if (domainCode.isPresent() || !UNIQUE_VIOLATION.equals(violation.getSQLState())) {
            return domainCode;
        }
        return Optional.of(ErrorCode.DUPLICATE_RECORD);
    }

    /**
     * Hibernate names only unique, check, foreign-key and not-null violations (23505, 23514, 23503,
     * 23502). The driver's copy of the server's error names every kind, an exclusion constraint's
     * 23P01 too, and doesn't depend on the server's message language.
     */
    private static Optional<String> constraintName(ConstraintViolationException violation) {
        if (Objects.nonNull(violation.getConstraintName())) {
            return Optional.of(violation.getConstraintName());
        }
        return serverError(violation).map(ServerErrorMessage::getConstraint);
    }

    /**
     * The server's error can't tell the side of a foreign key violation: it names the child table and the
     * constraint for a child's insert and for its parent's delete alike. The statement Hibernate ran can, and
     * unlike the message it doesn't depend on the server's language.
     */
    private static boolean isInsertOrUpdate(JDBCException violation) {
        String statement = Objects.requireNonNullElse(violation.getSQL(), "").stripLeading().toLowerCase(Locale.ROOT);
        return statement.startsWith("insert") || statement.startsWith("update");
    }

    private static Optional<ServerErrorMessage> serverError(Throwable failure) {
        for (Throwable cause = failure; Objects.nonNull(cause); cause = cause.getCause()) {
            if (cause instanceof PSQLException driverFailure) {
                return Optional.ofNullable(driverFailure.getServerErrorMessage());
            }
        }
        return Optional.empty();
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
