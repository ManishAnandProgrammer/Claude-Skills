package com.example.app.support;

import com.example.app.common.tenancy.TenantSchemas;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeSet;
import liquibase.exception.LiquibaseException;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The database the application meets when it upgrades a live one, for the whole test run: when
 * {@code app.test.upgrade.previous-release} names the release in production (the highest changeset numbers
 * it shipped, {@code <pieces>[,<project>]}; verify.sh passes it), Liquibase first applies that release's changesets alone, then
 * {@code db/upgrade/previous-release-seed.sql} fills the tables the way a used database holds them, and only
 * then does the application's own Liquibase run apply the changesets that came after, over that data.
 * Every test of the run then works on the upgraded database, and {@code UpgradeFromPreviousReleaseIntegrationTest}
 * checks what the new changesets did to the seeded rows (crud-gen tests.md, *Upgrading a used database*).
 *
 * <p>Changeset numbers come in two ranges: the house pieces' 0001 to 0099, a project's own from 0100. A piece
 * changed after a project's release appends its next number (0027) after the project's (0105), so one highest
 * number can't tell what shipped: the release names both, {@code 26,105} (the pieces' alone, {@code 23}, while
 * the project has none of its own). A changeset belongs to the new release when its file's number is above the
 * release's in its range: the run stops before the first such changeset, so a master lists every release's
 * changesets before the next one's (number order within each range; row mode's tenancy base first, which
 * bootstrap-copy.sh writes so). Without the property nothing happens.</p>
 *
 * <p>Schema per tenant: the application's own run is the platform changelog's. Each tenant the seed
 * records as provisioned ({@code tenant_migrations} MIGRATED, no last changeset yet) then gets its schema as
 * the previous release's provisioning left it: the tenant changelog up to that release, then
 * {@code db/upgrade/previous-release-tenant-seed.sql} in it ({@code upgrade.tenant} names the tenant to the
 * script). The deploy-time {@code TenantMigrations} run brings those schemas up, as at a real deploy.</p>
 */
public class PreviousReleaseDatabase implements BeanPostProcessor {

    static final String PREVIOUS_RELEASE = "app.test.upgrade.previous-release";

    private final Environment environment;
    private boolean upgraded;

    public PreviousReleaseDatabase(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        String previousRelease = environment.getProperty(PREVIOUS_RELEASE);
        if (bean instanceof SpringLiquibase application && !upgraded && Objects.nonNull(previousRelease)) {
            upgraded = true;
            migrateToThePreviousRelease(application, Release.parse(previousRelease));
            seed(application.getDataSource());
            provisionTheSeededTenants(application, Release.parse(previousRelease));
        }
        return bean;
    }

    private void migrateToThePreviousRelease(SpringLiquibase application, Release previousRelease) {
        Map<String, String> parameters = Binder.get(environment)
                .bind("spring.liquibase.parameters", Bindable.mapOf(String.class, String.class))
                .orElse(Map.of());
        PreviousReleaseLiquibase previous = new PreviousReleaseLiquibase(application, application.getChangeLog(), parameters,
                application.getDefaultSchema());
        try (Connection connection = application.getDataSource().getConnection()) {
            Liquibase liquibase = previous.open(connection);
            liquibase.update(changesetsOf(liquibase, previousRelease), new Contexts(application.getContexts()),
                    new LabelExpression(application.getLabelFilter()));
        } catch (LiquibaseException | SQLException failure) {
            throw new IllegalStateException("Migrating to the previous release failed", failure);
        }
    }

    /** On a connection of its own, committed statement by statement, as the role that migrates. */
    private static void seed(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/upgrade/previous-release-seed.sql"));
        } catch (SQLException failure) {
            throw new IllegalStateException("Seeding the previous release's database failed", failure);
        }
    }

    private void provisionTheSeededTenants(SpringLiquibase platform, Release previousRelease) {
        JdbcTemplate owner = new JdbcTemplate(platform.getDataSource());
        String appRole = owner.queryForObject("select quote_ident(?)", String.class,
                environment.getRequiredProperty("app.tenancy.schema.app-role"));
        List<UUID> tenants = owner.queryForList("""
                select tenant_id from platform.tenant_migrations where status = 'MIGRATED' and last_changeset is null
                 order by tenant_id""", UUID.class);
        for (UUID tenant : tenants) {
            String schema = TenantSchemas.of(tenant);
            owner.execute("create schema " + schema);
            owner.execute("grant usage on schema %s to %s".formatted(schema, appRole));
            migrateTenantSchema(platform, schema, previousRelease);
            owner.execute("revoke all on %1$s.databasechangelog, %1$s.databasechangeloglock from %2$s".formatted(schema, appRole));
            seedTenant(platform.getDataSource(), schema, tenant);
            owner.update("""
                    update platform.tenant_migrations set migrated_at = now(),
                           last_changeset = (select id from %s.databasechangelog order by orderexecuted desc limit 1)
                     where tenant_id = ?""".formatted(schema), tenant);
        }
    }

    private void migrateTenantSchema(SpringLiquibase platform, String schema, Release previousRelease) {
        PreviousReleaseLiquibase previous = new PreviousReleaseLiquibase(platform,
                environment.getRequiredProperty("app.tenancy.schema.tenant-changelog"), Map.of(), schema);
        try (Connection connection = platform.getDataSource().getConnection()) {
            Liquibase liquibase = previous.open(connection);
            liquibase.update(changesetsOf(liquibase, previousRelease), new Contexts(), new LabelExpression());
        } catch (LiquibaseException | SQLException failure) {
            throw new IllegalStateException("Migrating schema %s to the previous release failed".formatted(schema), failure);
        }
    }

    private static void seedTenant(DataSource dataSource, String schema, UUID tenant) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            JdbcTemplate session = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            session.execute("set search_path to " + schema);
            session.queryForObject("select set_config('upgrade.tenant', ?, false)", String.class, tenant.toString());
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/upgrade/previous-release-tenant-seed.sql"));
        } catch (SQLException failure) {
            throw new IllegalStateException("Seeding schema %s failed".formatted(schema), failure);
        }
    }

    /** How many changesets come before the first one of a file {@code previousRelease} didn't ship. */
    static int changesetsOf(Liquibase liquibase, Release previousRelease) throws LiquibaseException {
        int count = 0;
        for (ChangeSet changeSet : liquibase.getDatabaseChangeLog().getChangeSets()) {
            if (!previousRelease.shipped(numberOf(changeSet.getFilePath()))) {
                return count;
            }
            count++;
        }
        return count;
    }

    /** The changeset file's leading number (0024-tags-status-newest.sql: 24); 0 for a file without one. */
    private static int numberOf(String filePath) {
        String fileName = Path.of(filePath).getFileName().toString();
        int digits = 0;
        while (digits < fileName.length() && Character.isDigit(fileName.charAt(digits))) {
            digits++;
        }
        return digits == 0 ? 0 : Integer.parseInt(fileName.substring(0, digits));
    }

    /**
     * The highest changeset numbers a release shipped: the pieces' (below {@link #FIRST_PROJECT_NUMBER}) and
     * the project's own ({@link #FIRST_PROJECT_NUMBER} and up; one below it while the project has none).
     */
    record Release(int pieces, int project) {

        static final int FIRST_PROJECT_NUMBER = 100;

        /** {@code 26,105}, or {@code 23} for a release with no changeset of the project's own. */
        static Release parse(String release) {
            String[] numbers = release.split(",");
            int pieces = Integer.parseInt(numbers[0].strip());
            int project = numbers.length > 1 ? Integer.parseInt(numbers[1].strip()) : FIRST_PROJECT_NUMBER - 1;
            if (numbers.length > 2 || pieces >= FIRST_PROJECT_NUMBER || project < FIRST_PROJECT_NUMBER - 1) {
                throw new IllegalArgumentException("%s is <pieces>[,<project>]: the pieces' highest number below %d, the project's from it"
                        .formatted(release, FIRST_PROJECT_NUMBER));
            }
            return new Release(pieces, project);
        }

        /** Whether the file numbered {@code number} was in this release; a file without a number always is. */
        boolean shipped(int number) {
            return number < FIRST_PROJECT_NUMBER ? number <= pieces : number <= project;
        }
    }

    /**
     * The application's own Liquibase settings (changelog, resource loader, parameters, schemas), so the
     * changesets it applies are recorded under the same file paths and the application's run skips them.
     */
    private static final class PreviousReleaseLiquibase extends SpringLiquibase {

        PreviousReleaseLiquibase(SpringLiquibase application, String changeLog, Map<String, String> parameters, String schema) {
            setDataSource(application.getDataSource());
            setChangeLog(changeLog);
            setResourceLoader(application.getResourceLoader());
            setChangeLogParameters(parameters);
            setDefaultSchema(schema);
            setLiquibaseSchema(schema);
        }

        Liquibase open(Connection connection) throws LiquibaseException {
            return createLiquibase(connection);
        }
    }
}
