package com.example.app.archunit.fixture.settings;

import jakarta.validation.constraints.AssertTrue;

public record CheckedSettings(int poolSize, int maxConcurrent) {

    @AssertTrue
    public boolean isAdmissionAboveThePool() {
        return maxConcurrent >= 2 * poolSize;
    }
}
