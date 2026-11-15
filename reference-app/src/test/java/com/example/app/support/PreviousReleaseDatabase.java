package com.example.app.support;

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
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;

/**
 * The database the application meets when it upgrades a live one, for the whole test run: when
 * {@code app.test.upgrade.previous-release} names the release in production (the highest changeset number
 * it shipped; verify.sh passes it), Liquibase first applies that release's changesets alone, then
 * {@code db/upgrade/previous-release-seed.sql} fills the tables the way a used database holds them, and only
 * then does the application's own Liquibase run apply the changesets that came after, over that data.
 * Every test of the run then works on the upgraded database, and {@code UpgradeFromPreviousReleaseIntegrationTest}
 * checks what the new changesets did to the seeded rows (crud-gen tests.md, *Upgrading a used database*).
 *
 * <p>A changeset belongs to the new release when its file's number is higher than the previous release's:
 * the run stops before the first such changeset. Without the property nothing happens.</p>
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
            migrateToThePreviousRelease(application, Integer.parseInt(previousRelease));
            seed(application.getDataSource());
        }
        return bean;
    }

    private void migrateToThePreviousRelease(SpringLiquibase application, int previousRelease) {
        Map<String, String> parameters = Binder.get(environment)
                .bind("spring.liquibase.parameters", Bindable.mapOf(String.class, String.class))
                .orElse(Map.of());
        PreviousReleaseLiquibase previous = new PreviousReleaseLiquibase(application, parameters);
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

    /** How many changesets come before the first one of a file numbered after {@code previousRelease}. */
    static int changesetsOf(Liquibase liquibase, int previousRelease) throws LiquibaseException {
        int count = 0;
        for (ChangeSet changeSet : liquibase.getDatabaseChangeLog().getChangeSets()) {
            if (numberOf(changeSet.getFilePath()) > previousRelease) {
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
     * The application's own Liquibase settings (changelog, resource loader, parameters, schemas), so the
     * changesets it applies are recorded under the same file paths and the application's run skips them.
     */
    private static final class PreviousReleaseLiquibase extends SpringLiquibase {

        PreviousReleaseLiquibase(SpringLiquibase application, Map<String, String> parameters) {
            setDataSource(application.getDataSource());
            setChangeLog(application.getChangeLog());
            setResourceLoader(application.getResourceLoader());
            setChangeLogParameters(parameters);
            setDefaultSchema(application.getDefaultSchema());
            setLiquibaseSchema(application.getLiquibaseSchema());
        }

        Liquibase open(Connection connection) throws LiquibaseException {
            return createLiquibase(connection);
        }
    }
}
