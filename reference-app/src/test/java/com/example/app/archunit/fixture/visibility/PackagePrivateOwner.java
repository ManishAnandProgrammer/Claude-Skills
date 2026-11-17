package com.example.app.archunit.fixture.visibility;

/** A package-private class: its public members can't escape the package. */
class PackagePrivateOwner {

    private final Hidden hidden;

    public PackagePrivateOwner(Hidden hidden) {
        this.hidden = hidden;
    }

    public String describe() {
        return hidden.toString();
    }
}
