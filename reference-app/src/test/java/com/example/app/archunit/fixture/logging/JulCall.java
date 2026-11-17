package com.example.app.archunit.fixture.logging;

import java.util.logging.Logger;

/** java.util.logging through a static call, with no Logger field. */
public class JulCall {

    public void report() {
        Logger.getLogger(JulCall.class.getName()).info("reported");
    }
}
