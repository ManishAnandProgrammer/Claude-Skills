package com.example.app.config;

import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The settings house-review's configuration baseline (CFG-01, CFG-02, CFG-04, CFG-05) requires, read from
 * the bound environment as the application runs: a key a project drops or relaxes in config/*.yml fails the
 * build instead of a review. Values that differ per deployment (pool sizes, ports) are not here.
 */
class ConfigurationBaselineIntegrationTest extends IntegrationTest {

    @Autowired
    private Environment environment;

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', textBlock = """
            spring.datasource.hikari.connection-timeout                            | 3000
            spring.datasource.hikari.validation-timeout                            | 1000
            spring.datasource.hikari.keepalive-time                                | 30000
            spring.datasource.hikari.idle-timeout                                  | 120000
            spring.datasource.hikari.max-lifetime                                  | 1800000
            spring.datasource.hikari.leak-detection-threshold                      | 30000
            spring.datasource.hikari.transaction-isolation                         | TRANSACTION_READ_COMMITTED
            spring.datasource.hikari.auto-commit                                   | false
            spring.datasource.hikari.data-source-properties.socketTimeout          | 30
            spring.datasource.hikari.data-source-properties.reWriteBatchedInserts  | true
            spring.datasource.hikari.data-source-properties.tcpKeepAlive           | true
            spring.datasource.hikari.data-source-properties.logServerErrorDetail   | false
            spring.jpa.open-in-view                                                | false
            spring.jpa.show-sql                                                    | false
            spring.jpa.properties.hibernate.connection.provider_disables_autocommit | true
            spring.jpa.properties.hibernate.jdbc.time_zone                         | UTC
            spring.jpa.properties.hibernate.jdbc.batch_size                        | 30
            spring.jpa.properties.hibernate.order_inserts                          | true
            spring.jpa.properties.hibernate.order_updates                          | true
            spring.jpa.properties.hibernate.default_batch_fetch_size               | 30
            spring.jpa.properties.hibernate.query.fail_on_pagination_over_collection_fetch | true
            spring.jpa.properties.hibernate.query.in_clause_parameter_padding      | true
            spring.threads.virtual.enabled                                         | true
            server.shutdown                                                        | graceful
            spring.lifecycle.timeout-per-shutdown-phase                            | 30s
            spring.task.execution.mode                                             | force
            spring.jackson.deserialization.fail-on-unknown-properties              | true
            spring.jackson.deserialization.fail-on-invalid-subtype                 | true
            spring.jackson.deserialization.accept-float-as-int                     | false
            spring.jackson.deserialization.wrap-exceptions                         | true
            spring.jackson.mapper.allow-coercion-of-scalars                        | false
            spring.jackson.mapper.accept-case-insensitive-enums                    | false
            spring.jackson.default-leniency                                        | false
            spring.jackson.datatype.enum.fail-on-numbers-for-enums                 | true
            """)
    @DisplayName("A baseline setting has its required value")
    void aBaselineSettingHasItsValue(String key, String required) {
        assertThat(environment.getProperty(key)).as(key).isEqualTo(required);
    }

    @Test
    @DisplayName("The app pool's server-side limits are set: statement, lock and idle-in-transaction timeouts")
    void thePoolSetsItsServerSideLimits() {
        assertThat(environment.getProperty("spring.datasource.hikari.data-source-properties.options"))
                .contains("statement_timeout=25s", "lock_timeout=3s", "idle_in_transaction_session_timeout=30s");
    }

    @Test
    @DisplayName("Hibernate validates the schema Liquibase owns, never changes it")
    void hibernateOnlyValidates() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }

    @Test
    @DisplayName("No column is a large object: @Lob maps text to an oid, unreadable outside a transaction and never cleaned up")
    void noColumnIsALargeObject() {
        List<String> largeObjects = jdbc.queryForList("""
                select table_schema || '.' || table_name || '.' || column_name
                  from information_schema.columns
                 where data_type = 'oid'
                   and table_schema not in ('pg_catalog', 'information_schema')
                """, String.class);

        assertThat(largeObjects).isEmpty();
    }
}
