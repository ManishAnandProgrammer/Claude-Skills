package com.example.app.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiquibaseOwnerRoleTest {

    private static final JdbcConnectionDetails POOL = new JdbcConnectionDetails() {
        @Override
        public String getUsername() {
            return "app_role";
        }

        @Override
        public String getPassword() {
            return "secret";
        }

        @Override
        public String getJdbcUrl() {
            return "jdbc:postgresql://localhost:5432/app";
        }
    };

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "app_role"})
    @DisplayName("A missing owner role, or the pool's own, stops startup naming DB_OWNER_USER")
    void ownerRoleIsRequiredAndNotThePools(String ownerRole) {
        LiquibaseConnectionConfig.Settings settings =
                new LiquibaseConnectionConfig.Settings(Duration.ofSeconds(5), "app", ownerRole, "owner-secret");

        assertThatThrownBy(() -> new LiquibaseConnectionConfig().liquibaseDataSource(POOL, settings))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_OWNER_USER");
    }

    @ParameterizedTest
    @ValueSource(strings = {"campus-owner", "Campus_Owner", "owner;drop"})
    @DisplayName("An owner role name the changesets can't use unquoted stops startup")
    void roleNamesArePlainIdentifiers(String ownerRole) {
        LiquibaseConnectionConfig.Settings settings =
                new LiquibaseConnectionConfig.Settings(Duration.ofSeconds(5), "app", ownerRole, "owner-secret");

        assertThatThrownBy(() -> new LiquibaseConnectionConfig().liquibaseDataSource(POOL, settings))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("plain lower-case role names");
    }
}
