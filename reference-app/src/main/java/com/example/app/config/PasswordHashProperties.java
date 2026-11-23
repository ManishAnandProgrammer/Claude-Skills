package com.example.app.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.security")
record PasswordHashProperties(@Min(4) @Max(31) int passwordHashStrength) {
}
