package com.example.app.config;

import com.example.app.common.tenancy.TenantIdentifierResolver;
import com.example.app.common.tenancy.TransactionLocalSchemaProvider;
import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Hibernate's multi-tenancy: the resolver (every session opens with {@code TenantContext}'s tenant)
 * and the connection hook that tells the database which tenant a transaction is for. The hook needs
 * {@code hibernate.connection.handling_mode: DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION}
 * ({@code config/tenancy.yml}): in Spring's default mode a read-only transaction borrows its connection
 * when it begins, and the hook's statement would then run in every transaction, SQL or not.
 */
@Configuration(proxyBeanMethods = false)
class TenancyConfig {

    @Bean
    HibernatePropertiesCustomizer tenancyHibernateProperties(DataSource dataSource) {
        return properties -> {
            properties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, new TenantIdentifierResolver());
            properties.put(AvailableSettings.MULTI_TENANT_CONNECTION_PROVIDER, new TransactionLocalSchemaProvider(dataSource));
        };
    }
}
