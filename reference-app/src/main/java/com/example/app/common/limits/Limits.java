package com.example.app.common.limits;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The tunable limits under {@code app.limits.*} (config/limits.yml), read by key: the configuration
 * is the only place a number lives. A key that isn't configured is a bug and fails loudly;
 * {@code LimitsIntegrationTest} checks every key the code uses.
 */
@Component
public class Limits {

    private static final String PREFIX = "app.limits.";
    private static final String TEXT_PREFIX = PREFIX + "text.";

    private final Environment environment;

    public Limits(Environment environment) {
        this.environment = environment;
    }

    /** {@code app.limits.<key>}, e.g. {@code offset-page.max-size}. */
    public int get(String key) {
        return environment.getRequiredProperty(PREFIX + key, Integer.class);
    }

    /** {@code app.limits.<key>} for a number that may exceed an int, e.g. a size in bytes. */
    public long getLong(String key) {
        return environment.getRequiredProperty(PREFIX + key, Long.class);
    }

    /** {@code app.limits.text.<key>}, e.g. {@code todo.title}. */
    public int maxLength(String key) {
        return environment.getRequiredProperty(TEXT_PREFIX + key, Integer.class);
    }
}
