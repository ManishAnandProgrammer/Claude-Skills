package com.example.app.common.apiversion;

public record ApiVersion(int major) implements Comparable<ApiVersion> {

    private static final String PREFIX = "v";

    @Override
    public int compareTo(ApiVersion other) {
        return Integer.compare(major, other.major);
    }

    @Override
    public String toString() {
        return PREFIX + major;
    }
}
