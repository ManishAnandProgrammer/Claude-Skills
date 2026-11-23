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
 * The run stops before the first changeset the previous release didn't ship, so a master lists every release's
 * changesets before the next one's (number order within each range; row mode's tenancy base first).
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
        }
        return bean;
    }

    private void migrateToThePreviousRelease(SpringLiquibase application, Release previousRelease) {
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

    /** Committed statement by statement, as the role that migrates. */
    private static void seed(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/upgrade/previous-release-seed.sql"));
        } catch (SQLException failure) {
            throw new IllegalStateException("Seeding the previous release's database failed", failure);
        }
    }

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

    private static int numberOf(String filePath) {
        String fileName = Path.of(filePath).getFileName().toString();
        int digits = 0;
        while (digits < fileName.length() && Character.isDigit(fileName.charAt(digits))) {
            digits++;
        }
        return digits == 0 ? 0 : Integer.parseInt(fileName.substring(0, digits));
    }

    /**
     * A piece changed after a project's release appends its next number (0027) after the project's (0105), so one
     * highest number can't tell what shipped: a release names the pieces' and the project's own.
     */
    record Release(int pieces, int project) {

        static final int FIRST_PROJECT_NUMBER = 100;

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

        boolean shipped(int number) {
            return number < FIRST_PROJECT_NUMBER ? number <= pieces : number <= project;
        }
    }

    /**
     * The application's own settings, so the changesets it applies are recorded under the same file paths and the
     * application's run skips them.
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
