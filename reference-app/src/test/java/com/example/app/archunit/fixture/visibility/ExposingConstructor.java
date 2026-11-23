package com.example.app.archunit.fixture.visibility;

public class ExposingConstructor {

    private final Hidden hidden;

    public ExposingConstructor(Hidden hidden) {
        this.hidden = hidden;
    }

    public String describe() {
        return hidden.toString();
    }
}
