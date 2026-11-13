package com.example.app.user.service;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.security.breached-passwords")
record BreachedPasswordProperties(boolean enabled,
                                  @NotBlank String apiUrl,
                                  @NotNull Duration connectTimeout,
                                  @NotNull Duration readTimeout) {
}
