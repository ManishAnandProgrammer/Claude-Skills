package com.example.app.archunit.fixture.visibility;

/** A public class whose public constructor takes a package-private type. */
public class ExposingConstructor {

    private final Hidden hidden;

    public ExposingConstructor(Hidden hidden) {
        this.hidden = hidden;
    }

    public String describe() {
        return hidden.toString();
    }
}
