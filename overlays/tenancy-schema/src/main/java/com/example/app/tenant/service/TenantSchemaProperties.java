package com.example.app.tenant.service;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.tenancy.schema")
public record TenantSchemaProperties(@NotBlank String appRole,
                                     @NotBlank String tenantChangelog,
                                     @NotNull @Valid Migrations migrations) {

    /** @param runAtStartup off in tests, which run {@code TenantMigrations} themselves */
    public record Migrations(boolean runAtStartup) {
    }
}
