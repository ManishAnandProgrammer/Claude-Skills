package com.example.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Objects;

@ConfigurationProperties(prefix = "app.cors")
record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = Objects.isNull(allowedOrigins) ? List.of()
                : allowedOrigins.stream().map(String::strip).filter(origin -> !origin.isEmpty()).toList();
    }
}
