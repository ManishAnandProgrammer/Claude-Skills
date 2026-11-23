package com.example.app.archunit.fixture.visibility;

public class NarrowedConstructor {

    private final Hidden hidden;

    NarrowedConstructor(Hidden hidden) {
        this.hidden = hidden;
    }

    public String describe() {
        return hidden.toString();
    }
}
