package com.example.app.archunit.fixture.dto;

import jakarta.validation.constraints.AssertTrue;

/** A request body checking a rule across fields with a plain {@code @AssertTrue}: reported on a name no client sends. */
public record RangeRequest(int from, int to) {

    @AssertTrue
    public boolean isOrdered() {
        return from <= to;
    }
}
