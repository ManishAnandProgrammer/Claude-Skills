package com.example.app.tenant.service;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code app.tenancy.schema.*}: the schema-per-tenant mode's settings.
 *
 * @param appRole         the role the application's pool connects as: granted usage on every tenant schema
 * @param tenantChangelog the tenant master, run into every tenant schema and the template
 * @param migrations      the deploy-time run over every tenant schema
 */
@Validated
@ConfigurationProperties(prefix = "app.tenancy.schema")
public record TenantSchemaProperties(@NotBlank String appRole,
                                     @NotBlank String tenantChangelog,
                                     @NotNull @Valid Migrations migrations) {

    /**
     * @param runAtStartup whether an instance runs {@code TenantMigrations} once it is ready (off in tests,
     *                     which run it themselves)
     */
    public record Migrations(boolean runAtStartup) {
    }
}
