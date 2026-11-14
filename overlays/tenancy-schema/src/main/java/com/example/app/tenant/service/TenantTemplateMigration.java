package com.example.app.tenant.service;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/**
 * Migrates the {@code tenant_template} schema at every start, after the platform changelog (tenant
 * tables refer to platform ones) and before Hibernate validates the entities against it: Hibernate
 * validates one schema only, and this is the one that is always current. Instances starting together
 * take turns on an advisory lock of the template's own ({@code TenantSchemaMigrator.migrateTemplate}):
 * Liquibase's lock table doesn't exist yet in a new schema, so it can't serialise them.
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
