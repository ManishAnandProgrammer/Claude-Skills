package com.example.app.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import javax.sql.DataSource;
import java.time.Duration;

/**
 * Liquibase's own connection to the application's database, with a lock timeout and no statement
 * timeout (why: {@code app.liquibase} in {@code config/db.yml}). A data source of the driver's,
 * because the setting is a connection option, which Boot's Liquibase properties can't carry.
 */
@Configuration
class LiquibaseConnectionConfig {

    /** Not a default candidate: the pool stays the auto-configured data source, the only one JPA sees. */
    @Bean(defaultCandidate = false)
    @LiquibaseDataSource
    DataSource liquibaseDataSource(JdbcConnectionDetails database, Settings settings) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(database.getJdbcUrl());
        dataSource.setUser(database.getUsername());
        dataSource.setPassword(database.getPassword());
        dataSource.setOptions("-c lock_timeout=" + settings.lockTimeout().toMillis());
        dataSource.setApplicationName(settings.applicationName());
        // A failed backfill or unique index would otherwise log the rows' values (config/db.yml, the pool's
        // logServerErrorDetail). The operator finds the rows with a query on a restored copy instead.
        dataSource.setLogServerErrorDetail(false);
        return dataSource;
    }

    @Validated
    @ConfigurationProperties(prefix = "app.liquibase")
    record Settings(@NotNull Duration lockTimeout, @NotBlank String applicationName) {
    }
}
