package com.example.app.common.exception;

import java.util.Map;

/**
 * A module's database constraints whose violation has a domain meaning: a unique constraint that
 * means "taken", a foreign key that means "still in use". Each module declares its own as a bean, so
 * the error handler maps them without {@code common} naming any feature's tables.
 */
public interface ConstraintErrorCodes {

    /** Constraint name, exactly as in the Liquibase changeset, to the code its violation answers. */
    Map<String, ErrorCode> byConstraintName();
}
