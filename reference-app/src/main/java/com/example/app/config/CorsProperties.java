package com.example.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Objects;

/**
 * {@code app.cors.allowed-origins}: the browser origins allowed to call the API, comma-separated
 * in {@code CORS_ALLOWED_ORIGINS}. Credentials are never allowed: auth is a header, not a cookie.
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = Objects.isNull(allowedOrigins) ? List.of()
                : allowedOrigins.stream().map(String::strip).filter(origin -> !origin.isEmpty()).toList();
    }
}
