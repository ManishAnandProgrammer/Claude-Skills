package com.example.app.common.exception;

import java.util.Map;

public interface ConstraintErrorCodes {

    /** A foreign key listed here answers this code when a delete trips it (the parent is still in use). */
    Map<String, ErrorCode> byConstraintName();

    /**
     * Foreign keys tripped by an insert or update: the parent the service had just read was deleted
     * before the row was written.
     */
    default Map<String, ErrorCode> byForeignKeyOnWrite() {
        return Map.of();
    }
}
