package com.example.app.tenant.service;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/**
 * Migrates the {@code tenant_template} schema at every start, after the platform changelog (tenant
 * tables refer to platform ones) and before Hibernate validates the entities against it: Hibernate
 * validates one schema only, and this is the one that is always current. Liquibase's own lock
 * serialises instances starting together, as it does for the platform changelog.
 */
@Component
@DependsOn("liquibase")
class TenantTemplateMigration implements InitializingBean {

    private final TenantSchemaMigrator migrator;

    TenantTemplateMigration(TenantSchemaMigrator migrator) {
        this.migrator = migrator;
    }

    @Override
    public void afterPropertiesSet() {
        migrator.migrateTemplate();
    }

    /** Hibernate's entity manager factory waits for the template. */
    @Component
    static class EntityManagerFactoryWaitsForTemplate extends EntityManagerFactoryDependsOnPostProcessor {

        EntityManagerFactoryWaitsForTemplate() {
            super(TenantTemplateMigration.class);
        }
    }
}
