package com.example.app.archunit.fixture.visibility;

/** The fix: the constructor that takes the package-private type is package-private too. */
public class NarrowedConstructor {

    private final Hidden hidden;

    NarrowedConstructor(Hidden hidden) {
        this.hidden = hidden;
    }

    public String describe() {
        return hidden.toString();
    }
}
