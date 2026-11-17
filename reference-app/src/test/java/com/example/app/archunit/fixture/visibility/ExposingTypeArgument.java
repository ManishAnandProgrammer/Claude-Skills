package com.example.app.archunit.fixture.visibility;

import java.util.List;

/** A public class whose public method names a package-private type as a type argument. */
public class ExposingTypeArgument {

    public List<Hidden> all() {
        return List.of(new Hidden());
    }
}
