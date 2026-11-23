package com.example.app.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * A data source of the driver's: the lock timeout is a connection option, which Boot's Liquibase properties can't
 * carry. It connects as the owner role, never the pool's: the isolation rests on the pool's role owning nothing.
 */
@Configuration
class LiquibaseConnectionConfig {

    private static final Pattern PLAIN_ROLE_NAME = Pattern.compile("[a-z_][a-z0-9_]*");

    /** Not a default candidate, so Boot still auto-configures the pool, the only data source JPA sees. */
    @Bean(defaultCandidate = false)
    @LiquibaseDataSource
    DataSource liquibaseDataSource(JdbcConnectionDetails database, Settings settings) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(database.getJdbcUrl());
        if (!StringUtils.hasText(settings.username()) || settings.username().equals(database.getUsername())) {
            throw new IllegalStateException("""
                    app.liquibase.username (DB_OWNER_USER) must name the owner role, another role than the pool's \
                    (DB_USER): migrating as the pool's role makes it the owner of every table (docs/tenancy-runbook.md)""");
        }
        // The tenancy changesets grant to the pool's role by name, unquoted (0013): a dash or a capital
        // there is a syntax error or another role.
        if (!PLAIN_ROLE_NAME.matcher(database.getUsername()).matches() || !PLAIN_ROLE_NAME.matcher(settings.username()).matches()) {
            throw new IllegalStateException("""
                    DB_USER and DB_OWNER_USER must be plain lower-case role names (letters, digits, underscores; \
                    campus_app, not campus-app): the changesets name them unquoted""");
        }
        dataSource.setUser(settings.username());
        dataSource.setPassword(settings.password());
        dataSource.setOptions("-c lock_timeout=" + settings.lockTimeout().toMillis());
        dataSource.setApplicationName(settings.applicationName());
        // A failed backfill or unique index would otherwise log the rows' values.
        dataSource.setLogServerErrorDetail(false);
        return dataSource;
    }

    @Validated
    @ConfigurationProperties(prefix = "app.liquibase")
    record Settings(@NotNull @DurationMin(millis = 1) Duration lockTimeout, @NotBlank String applicationName, String username, String password) {
    }
}
