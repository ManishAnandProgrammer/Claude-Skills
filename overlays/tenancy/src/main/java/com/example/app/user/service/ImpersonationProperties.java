package com.example.app.user.service;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * {@code app.tenancy.impersonation.*}.
 *
 * @param tokenTtl how long an impersonation token works; no refresh token: staff ask again
 */
@Validated
@ConfigurationProperties(prefix = "app.tenancy.impersonation")
public record ImpersonationProperties(@NotNull @DurationMin(seconds = 1) Duration tokenTtl) {
}
