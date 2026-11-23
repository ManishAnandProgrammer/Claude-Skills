package com.example.app.tenant.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/**
 * After the platform changelog (tenant tables refer to platform ones) and before Hibernate validates the entities
 * against the template. Instances starting together take turns on an advisory lock of the template's own: Liquibase's
 * lock table doesn't exist yet in a new schema, so it can't serialise them.
 */
@Component
@DependsOn("liquibase")
@RequiredArgsConstructor
class TenantTemplateMigration implements InitializingBean {

    private final TenantSchemaMigrator migrator;

    @Override
    public void afterPropertiesSet() {
        migrator.migrateTemplate();
    }

    @Component
    static class EntityManagerFactoryWaitsForTemplate extends EntityManagerFactoryDependsOnPostProcessor {

        // Hand-written: the superclass takes the bean to wait for, which Lombok can't pass on.
        EntityManagerFactoryWaitsForTemplate() {
            super(TenantTemplateMigration.class);
        }
    }
}
