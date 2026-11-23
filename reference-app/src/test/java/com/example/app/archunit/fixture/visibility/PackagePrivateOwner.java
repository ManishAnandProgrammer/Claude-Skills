package com.example.app.archunit.fixture.visibility;

class PackagePrivateOwner {

    private final Hidden hidden;

    public PackagePrivateOwner(Hidden hidden) {
        this.hidden = hidden;
    }

    public String describe() {
        return hidden.toString();
    }
}
