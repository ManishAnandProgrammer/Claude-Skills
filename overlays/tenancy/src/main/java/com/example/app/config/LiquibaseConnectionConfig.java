package com.example.app.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

/**
 * Liquibase's own connection to the application's database, with a lock timeout and no statement
 * timeout (why: {@code app.liquibase} in {@code config/db.yml}). A data source of the driver's,
 * because the setting is a connection option, which Boot's Liquibase properties can't carry. It
 * connects as {@code app.liquibase.username} when set (a migration role that owns the tables, while
 * the pool's role only reads and writes them), else with the pool's credentials.
 */
@Configuration
class LiquibaseConnectionConfig {

    /** Not a default candidate: the pool stays the auto-configured data source, the only one JPA sees. */
    @Bean(defaultCandidate = false)
    @LiquibaseDataSource
    DataSource liquibaseDataSource(JdbcConnectionDetails database, Settings settings) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(database.getJdbcUrl());
        boolean ownRole = StringUtils.hasText(settings.username());
        dataSource.setUser(ownRole ? settings.username() : database.getUsername());
        dataSource.setPassword(ownRole ? settings.password() : database.getPassword());
        dataSource.setOptions("-c lock_timeout=" + settings.lockTimeout().toMillis());
        dataSource.setApplicationName(settings.applicationName());
        return dataSource;
    }

    @Validated
    @ConfigurationProperties(prefix = "app.liquibase")
    record Settings(@NotNull Duration lockTimeout, @NotBlank String applicationName, String username, String password) {
    }
}
