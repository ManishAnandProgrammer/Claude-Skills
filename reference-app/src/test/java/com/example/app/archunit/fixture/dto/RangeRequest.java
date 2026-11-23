package com.example.app.archunit.fixture.dto;

import jakarta.validation.constraints.AssertTrue;

public record RangeRequest(int from, int to) {

    @AssertTrue
    public boolean isOrdered() {
        return from <= to;
    }
}
