package com.example.app.archunit.fixture.logging;

import java.util.logging.Logger;

public class JulCall {

    public void report() {
        Logger.getLogger(JulCall.class.getName()).info("reported");
    }
}
