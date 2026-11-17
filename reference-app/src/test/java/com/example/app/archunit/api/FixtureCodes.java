package com.example.app.archunit.api;

/** A module contract's compile-time constants (an annotation's pattern): no record, enum or interface can hold one. */
public final class FixtureCodes {

    public static final String PATTERN = "[A-Z]{2,8}";

    private FixtureCodes() {
    }
}
