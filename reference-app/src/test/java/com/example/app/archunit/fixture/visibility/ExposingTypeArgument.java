package com.example.app.archunit.fixture.visibility;

import java.util.List;

public class ExposingTypeArgument {

    public List<Hidden> all() {
        return List.of(new Hidden());
    }
}
