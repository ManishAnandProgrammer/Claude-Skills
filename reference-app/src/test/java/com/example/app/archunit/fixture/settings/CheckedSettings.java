package com.example.app.archunit.fixture.settings;

import jakarta.validation.constraints.AssertTrue;

/** A settings record's startup check: no client sends it, so no field of a request is named. */
public record CheckedSettings(int poolSize, int maxConcurrent) {

    @AssertTrue
    public boolean isAdmissionAboveThePool() {
        return maxConcurrent >= 2 * poolSize;
    }
}
