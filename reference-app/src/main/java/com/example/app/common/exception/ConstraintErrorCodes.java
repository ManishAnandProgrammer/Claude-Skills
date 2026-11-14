package com.example.app.common.exception;

import java.util.Map;

/**
 * A module's database constraints whose violation has a domain meaning: a unique constraint that
 * means "taken", a foreign key that means "still in use". Each module declares its own as a bean, so
 * the error handler maps them without {@code common} naming any feature's tables.
 */
public interface ConstraintErrorCodes {

    /**
     * Constraint name, exactly as in the Liquibase changeset, to the code its violation answers. A
     * foreign key listed here answers this code when a delete trips it (the parent is still in use).
     */
    Map<String, ErrorCode> byConstraintName();

    /**
     * Foreign keys tripped by an insert or update: the parent the service had just read was deleted
     * before the row was written. Each maps to the parent's not-found code without placeholders (404).
     * PostgreSQL names the child table and the same constraint on both sides, so only the failed
     * statement tells them apart.
     */
    default Map<String, ErrorCode> byForeignKeyOnWrite() {
        return Map.of();
    }
}
