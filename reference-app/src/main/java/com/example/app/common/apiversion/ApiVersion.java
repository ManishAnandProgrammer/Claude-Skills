package com.example.app.common.apiversion;

/**
 * An API version as clients write it in the path: {@code v} and a major number. Its text form is
 * the one clients send, so logs and metrics show {@code v1}, never a parsed {@code 1.0.0}.
 */
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
