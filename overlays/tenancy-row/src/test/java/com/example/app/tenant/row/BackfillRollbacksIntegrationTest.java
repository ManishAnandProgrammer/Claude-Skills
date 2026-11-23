package com.example.app.tenant.row;

import com.example.app.support.IntegrationTest;
import com.example.app.support.WithoutTestTenant;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * A rollback runs as the owner with no tenant set, where the forced policy hides every row: one that deletes or
 * updates rows of a tenant table without lifting {@code FORCE ROW LEVEL SECURITY} itself changes nothing, and the
 * previous release then meets rows it doesn't know (a policy of a purpose it has no constant for answers 500).
 */
@WithoutTestTenant
class BackfillRollbacksIntegrationTest extends IntegrationTest {

    private static final Pattern ROW_WRITE = Pattern.compile("^\\s*(?:delete\\s+from|update|insert\\s+into)\\s+(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern INSERT = Pattern.compile("^\\s*insert\\s+into\\s+(\\w+)", Pattern.CASE_INSENSITIVE);

    @Autowired
    private ResourcePatternResolver resources;

    @Autowired
    private DataSource dataSource;

    @Value("${app.liquibase.username}")
    private String ownerUser;

    @Value("${app.liquibase.password}")
    private String ownerPassword;

    private record Changeset(String id, List<String> statements, List<String> rollback) {

        boolean liftsForce() {
            return statements.stream().anyMatch(statement -> statement.toUpperCase(Locale.ROOT).contains("NO FORCE ROW LEVEL SECURITY"));
        }
    }

    @Test
    @DisplayName("""
            A rollback that writes rows of a table under a forced policy lifts FORCE on it around the write, and a \
            backfill that inserts rows has a rollback that removes them (a DELETE so wrapped, or the table dropped)""")
    void rollbacksOfBackfillsLiftTheForcedPolicy() throws IOException {
        List<String> wrong = new ArrayList<>();
        for (Changeset changeset : changesets()) {
            for (int index = 0; index < changeset.rollback().size(); index++) {
                Matcher write = ROW_WRITE.matcher(changeset.rollback().get(index));
                if (write.find() && isForced(write.group(1)) && !forceLiftedAround(changeset.rollback(), index, write.group(1))) {
                    wrong.add(changeset.id() + ": its rollback writes " + write.group(1) + " under the forced policy");
                }
            }
            if (changeset.liftsForce()) {
                for (String statement : changeset.statements()) {
                    Matcher insert = INSERT.matcher(statement);
                    if (insert.find() && !removes(changeset.rollback(), insert.group(1))) {
                        wrong.add(changeset.id() + ": its rollback leaves the rows it inserted into " + insert.group(1));
                    }
                }
            }
        }
        assertThat(wrong).isEmpty();
    }

    @Test
    @DisplayName("""
            Run as the owner, each backfill's rollback deletes every row its DELETE names, as many as the owner \
            finds with the policy lifted, and leaves the policy forced (all rolled back afterwards)""")
    void backfillRollbacksDeleteTheirRows() throws IOException, SQLException {
        testTenant();
        List<Changeset> all = changesets();
        List<Changeset> deleting = new ArrayList<>();
        for (int index = 0; index < all.size(); index++) {
            Changeset changeset = all.get(index);
            if (changeset.liftsForce() && changeset.rollback().stream().anyMatch(BackfillRollbacksIntegrationTest::deletes)
                    && !referencedLater(all.subList(index + 1, all.size()), changeset)) {
                deleting.add(changeset);
            }
        }
        assumeFalse(deleting.isEmpty(), "no backfill with a deleting rollback in this copy (no piece seeds tenant rows)");
        int deletedInAll = 0;
        for (Changeset changeset : deleting) {
            try (Connection owner = DriverManager.getConnection(dataSource.unwrap(HikariDataSource.class).getJdbcUrl(), ownerUser, ownerPassword)) {
                owner.setAutoCommit(false);
                JdbcTemplate asOwner = new JdbcTemplate(new SingleConnectionDataSource(owner, true));
                try {
                    List<Integer> there = new ArrayList<>();
                    for (String statement : changeset.rollback()) {
                        if (deletes(statement)) {
                            there.add(rowsTheDeleteMatches(asOwner, statement));
                        }
                    }
                    List<Integer> deleted = new ArrayList<>();
                    for (String statement : changeset.rollback()) {
                        if (deletes(statement)) {
                            deleted.add(asOwner.update(statement));
                        } else {
                            asOwner.execute(statement);
                        }
                    }
                    assertThat(deleted).as(changeset.id() + ": rows its rollback deleted, against the rows there").isEqualTo(there);
                    for (String statement : changeset.rollback()) {
                        if (deletes(statement)) {
                            assertThat(forced(asOwner, statement)).as(changeset.id() + ": the policy forced again").isTrue();
                        }
                    }
                    deletedInAll += there.stream().mapToInt(Integer::intValue).sum();
                } finally {
                    owner.rollback();
                }
            }
        }
        assertThat(deletedInAll).as("rows the rollbacks deleted (the run's tenants have their seeds)").isPositive();
    }

    /**
     * A real rollback undoes the later changeset first, so this rollback can't run alone on today's schema. The static
     * test above still checks it.
     */
    private static boolean referencedLater(List<Changeset> later, Changeset changeset) {
        for (String statement : changeset.rollback()) {
            Matcher table = ROW_WRITE.matcher(statement);
            if (deletes(statement) && table.find()) {
                String references = "references " + table.group(1).toLowerCase(Locale.ROOT) + " ";
                boolean referenced = later.stream().flatMap(next -> next.statements().stream())
                        .anyMatch(next -> next.toLowerCase(Locale.ROOT).contains(references));
                if (referenced) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean deletes(String statement) {
        return statement.toLowerCase(Locale.ROOT).startsWith("delete from");
    }

    private static int rowsTheDeleteMatches(JdbcTemplate asOwner, String delete) {
        Matcher table = ROW_WRITE.matcher(delete);
        assertThat(table.find()).isTrue();
        String rest = delete.substring(table.end());
        asOwner.execute("alter table " + table.group(1) + " no force row level security");
        Integer there = asOwner.queryForObject("select count(*) from " + table.group(1) + rest, Integer.class);
        asOwner.execute("alter table " + table.group(1) + " force row level security");
        return there;
    }

    private static boolean forced(JdbcTemplate asOwner, String delete) {
        Matcher table = ROW_WRITE.matcher(delete);
        assertThat(table.find()).isTrue();
        return asOwner.queryForObject("select relforcerowsecurity from pg_class where relname = ?", Boolean.class, table.group(1));
    }

    private boolean isForced(String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select coalesce(bool_or(c.relforcerowsecurity), false)
                  from pg_class c
                 where c.relname = lower(?) and c.relkind = 'r'""", Boolean.class, table));
    }

    private static boolean forceLiftedAround(List<String> rollback, int index, String table) {
        String lift = "alter table " + table.toLowerCase(Locale.ROOT) + " no force row level security";
        String restore = "alter table " + table.toLowerCase(Locale.ROOT) + " force row level security";
        boolean lifted = rollback.subList(0, index).stream().anyMatch(line -> line.toLowerCase(Locale.ROOT).startsWith(lift));
        boolean restored = rollback.subList(index + 1, rollback.size()).stream().anyMatch(line -> line.toLowerCase(Locale.ROOT).startsWith(restore));
        return lifted && restored;
    }

    private static boolean removes(List<String> rollback, String table) {
        String drop = "drop table " + table.toLowerCase(Locale.ROOT);
        String delete = "delete from " + table.toLowerCase(Locale.ROOT);
        return rollback.stream().map(line -> line.toLowerCase(Locale.ROOT)).anyMatch(line -> line.startsWith(drop) || line.startsWith(delete));
    }

    private List<Changeset> changesets() throws IOException {
        List<Changeset> changesets = new ArrayList<>();
        List<Resource> files = new ArrayList<>(List.of(resources.getResources("classpath*:db/changelog/changes/*.sql")));
        // In number order, the order the master runs them in.
        files.sort(Comparator.comparing(Resource::getFilename));
        for (Resource file : files) {
            changesets.addAll(parse(file.getContentAsString(StandardCharsets.UTF_8)));
        }
        assertThat(changesets).isNotEmpty();
        return changesets;
    }

    private static List<Changeset> parse(String changelog) {
        List<Changeset> changesets = new ArrayList<>();
        String id = null;
        List<String> statements = new ArrayList<>();
        List<String> rollback = new ArrayList<>();
        StringBuilder statement = new StringBuilder();
        for (String line : changelog.lines().toList()) {
            if (line.startsWith("--changeset ")) {
                if (Objects.nonNull(id)) {
                    changesets.add(new Changeset(id, statements, rollback));
                }
                id = line.substring(line.indexOf(':') + 1).trim();
                statements = new ArrayList<>();
                rollback = new ArrayList<>();
            } else if (line.startsWith("--rollback ")) {
                rollback.add(line.substring("--rollback ".length()).trim().replaceAll(";$", ""));
            } else if (id != null && !line.startsWith("--") && !line.isBlank()) {
                statement.append(line.trim()).append(' ');
                if (line.trim().endsWith(";")) {
                    statements.add(statement.toString().trim());
                    statement.setLength(0);
                }
            }
        }
        if (Objects.nonNull(id)) {
            changesets.add(new Changeset(id, statements, rollback));
        }
        return changesets;
    }
}
