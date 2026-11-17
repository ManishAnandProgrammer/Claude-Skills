package com.example.app.archunit.api;

/** A class with behaviour in a module's api package: not a contract. */
public final class FixtureHelper {

    private FixtureHelper() {
    }

    public static String normalise(String code) {
        return code.trim();
    }
}
