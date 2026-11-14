package com.example.app.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code app.security.password-hash-strength}: bcrypt's cost (each step doubles the work). Exactly 12
 * wherever real passwords are hashed ({@link StartupChecks}): a setting only so the test profile, alone,
 * can run lower, because every test account costs a hash at signup and another at login.
 *
 * @param passwordHashStrength bcrypt's log2 rounds, 4 to 31 (the algorithm's range)
 */
@Validated
@ConfigurationProperties(prefix = "app.security")
record PasswordHashProperties(@Min(4) @Max(31) int passwordHashStrength) {
}
