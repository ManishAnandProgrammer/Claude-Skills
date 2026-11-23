package com.example.app.common.limits;

import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
public class Limits {

    private static final String PREFIX = "app.limits.";
    private static final String TEXT_PREFIX = PREFIX + "text.";

    private final Environment environment;

    public int get(String key) {
        return environment.getRequiredProperty(PREFIX + key, Integer.class);
    }

    public long getLong(String key) {
        return environment.getRequiredProperty(PREFIX + key, Long.class);
    }

    public BigDecimal getDecimal(String key) {
        return environment.getRequiredProperty(PREFIX + key, BigDecimal.class);
    }

    public int maxLength(String key) {
        return environment.getRequiredProperty(TEXT_PREFIX + key, Integer.class);
    }
}
