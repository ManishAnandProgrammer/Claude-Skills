package com.example.app.common.persistence;

import com.example.app.support.IntegrationTest;
import com.example.app.support.QueryPlan;
// tenancy:begin
// import com.example.app.support.WithoutTestTenant;
// tenancy:end
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The change history (the change-history piece, house-review DATA-08): what the triggers write, who they name, what
 * they never copy, that the table is append-only, and the purge past the retention.
 */
// tenancy:begin
// // The purge is a platform job, which runs outside any tenant; requests name their tenant in their token.
// @WithoutTestTenant
// tenancy:end
class ChangeHistoryIntegrationTest extends IntegrationTest {

    // tenancy-schema:begin
    // /** The platform's table: a test's statements run in the test tenant's schema. */
    // private static final String AUDIT_LOG = "platform.audit_log";
    // tenancy-schema:else
    private static final String AUDIT_LOG = "audit_log";
    // tenancy-schema:end
    // tenancy-schema:begin
    // private static final String AUDIT_FUNCTION = "platform.audit_row_change";
    // tenancy-schema:else
    private static final String AUDIT_FUNCTION = "audit_row_change";
    // tenancy-schema:end

    @Autowired
    private ChangeHistoryPurgeJob purgeJob;

    @Autowired
    private ChangeHistoryPurgeJob.Settings settings;

    @Autowired
    private ChangeHistoryRetention retention;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * The tables the change history audits, each piece's own (the review profile's {@code auditedTables}): a table
     * that matters gets its trigger in the changeset that creates it, and its name here.
     */
    private static Set<String> auditedTables() {
        Set<String> tables = new HashSet<>();
        // sign-in:begin
        tables.add("users");
        // tenancy:begin
        // tables.add("tenant_memberships");
        // tenancy:end
        // sign-in:end
        // example:begin
        tables.add("todo_lists");
        tables.add("todos");
        // example:end
        // roles:begin
        // tables.addAll(List.of("roles", "role_permissions", "user_roles", "user_permissions"));
        // roles:end
        return tables;
    }

    /** The history of one row, oldest first. */
    private List<Map<String, Object>> historyOf(String table, UUID rowId) {
        return jdbc.queryForList("""
                select operation, old_row::text as old_row, new_row::text as new_row, actor_id
                  from %s
                 where table_name = ? and row_id = ?
                 order by changed_at, id""".formatted(AUDIT_LOG), table, rowId);
    }
    // sign-in:begin

    @Test
    @DisplayName("A signup writes the new account's row, without its password hash and without an actor (nobody is signed in)")
    void anInsertKeepsTheNewRowWithoutItsSecrets() throws Exception {
        TestUser user = newUser();

        List<Map<String, Object>> history = historyOf("users", user.id());

        assertThat(history).first().satisfies(insert -> {
            assertThat(insert.get("operation")).isEqualTo("INSERT");
            assertThat((String) insert.get("new_row")).contains(user.email()).doesNotContain("password_hash");
            assertThat(insert.get("old_row")).isNull();
            assertThat(insert.get("actor_id")).isNull();
        });
    }

    @Test
    @DisplayName("A signed-in write names its caller, and an update keeps only the columns that changed, both sides")
    void anUpdateKeepsTheChangedColumnsAndItsCaller() throws Exception {
        TestUser user = newUser();

        postAs(user.accessToken(), "/api/v1/users/me/password", """
                {"currentPassword": "%s", "newPassword": "Another-Horse-10"}
                """.formatted(PASSWORD)).andExpect(status().isOk());

        List<Map<String, Object>> history = historyOf("users", user.id());
        assertThat(history).last().satisfies(update -> {
            assertThat(update.get("operation")).isEqualTo("UPDATE");
            assertThat(update.get("actor_id")).isEqualTo(user.id());
            // The hash is the change, and it is never copied: what is left is the bookkeeping that moved with it.
            assertThat((String) update.get("new_row")).contains("version").doesNotContain("password_hash", "email");
            assertThat((String) update.get("old_row")).contains("version").doesNotContain("password_hash", "email");
        });
    }

    @Test
    @DisplayName("The caller is transaction-local: the next transaction on a pooled connection names nobody")
    void theActorEndsWithItsTransaction() throws Exception {
        TestUser user = newUser();
        postAs(user.accessToken(), "/api/v1/users/me/password", """
                {"currentPassword": "%s", "newPassword": "Another-Horse-10"}
                """.formatted(PASSWORD)).andExpect(status().isOk());

        assertThat(sql("update users set full_name = ? where id = ?", "Renamed by an operator", user.id())).isEqualTo(1);

        assertThat(historyOf("users", user.id())).last().satisfies(update -> {
            assertThat((String) update.get("new_row")).contains("Renamed by an operator");
            assertThat(update.get("actor_id")).isNull();
        });
    }

    // sign-in:end

    @Test
    @DisplayName("Every operation keeps what it changed, never the trigger's excluded columns; an update that changes nothing writes nothing")
    void eachOperationKeepsWhatItChanged() {
        UUID id = UUID.randomUUID();
        // One statement, so one transaction and one session for the temporary table and its trigger.
        sql("""
                DO $$
                BEGIN
                    CREATE TEMP TABLE change_history_probe (id uuid PRIMARY KEY, name text, secret text) ON COMMIT DROP;
                    CREATE TRIGGER audit_change_history_probe AFTER INSERT OR UPDATE OR DELETE ON change_history_probe
                        FOR EACH ROW EXECUTE FUNCTION %s('secret');
                    INSERT INTO change_history_probe VALUES ('%s', 'first', 'hidden');
                    UPDATE change_history_probe SET name = name;
                    UPDATE change_history_probe SET name = 'second', secret = 'hidden too';
                    DELETE FROM change_history_probe;
                END $$""".formatted(AUDIT_FUNCTION, id));

        List<Map<String, Object>> history = historyOf("change_history_probe", id);

        assertThat(history).extracting(row -> row.get("operation")).containsExactly("INSERT", "UPDATE", "DELETE");
        assertThat(history.get(0).get("new_row")).isEqualTo("{\"id\": \"%s\", \"name\": \"first\"}".formatted(id));
        assertThat(history.get(1).get("old_row")).isEqualTo("{\"name\": \"first\"}");
        assertThat(history.get(1).get("new_row")).isEqualTo("{\"name\": \"second\"}");
        assertThat(history.get(2).get("old_row")).isEqualTo("{\"id\": \"%s\", \"name\": \"second\"}".formatted(id));
        assertThat(history.get(2).get("new_row")).isNull();
    }
    // example:begin

    @Test
    @DisplayName("The example's tables are audited: a todo list's create names its owner")
    void theExamplesTablesAreAudited() throws Exception {
        TestUser user = newUser();
        String listId = read(postAs(user.accessToken(), "/api/v1/todo-lists", """
                {"name": "Audited %s"}
                """.formatted(UUID.randomUUID())).andExpect(status().isCreated()), "$.response.id");

        assertThat(historyOf("todo_lists", UUID.fromString(listId))).singleElement().satisfies(insert -> {
            assertThat(insert.get("operation")).isEqualTo("INSERT");
            assertThat(insert.get("actor_id")).isEqualTo(user.id());
        });
    }
    // example:end

    @Test
    @DisplayName("""
            Every audited table has the history trigger, and every secret-like column of one (a hash, a secret, a token, \
            a password) is among the columns its trigger leaves out""")
    void everyAuditedTableHasItsTriggerAndLeavesOutItsSecrets() {
        Map<String, String> leftOut = new HashMap<>();
        // Every schema's: with schema per tenant each tenant schema has its own triggers on its own tables.
        for (Map<String, Object> trigger : jdbc.queryForList("""
                select c.relname as table_name, encode(t.tgargs, 'escape') as arguments
                  from pg_trigger t
                  join pg_class c on c.oid = t.tgrelid
                  join pg_proc p on p.oid = t.tgfoid
                 where p.proname = 'audit_row_change' and not t.tgisinternal""")) {
            leftOut.put((String) trigger.get("table_name"), (String) trigger.get("arguments"));
        }
        Set<String> audited = auditedTables();
        assertThat(leftOut.keySet()).as("the tables with the history trigger").isEqualTo(audited);

        List<Map<String, Object>> secretLike = jdbc.queryForList("""
                select distinct table_name, column_name
                  from information_schema.columns
                 where table_name = any (?) and column_name ~ '(hash|secret|token|password)'""",
                (Object) audited.toArray(new String[0]));
        // sign-in:begin
        assertThat(secretLike).as("the accounts' password hash, among the columns checked").isNotEmpty();
        // sign-in:end
        assertThat(secretLike).allSatisfy(column -> assertThat(leftOut.get((String) column.get("table_name")))
                .as("%s.%s left out of the history", column.get("table_name"), column.get("column_name"))
                .contains((String) column.get("column_name")));
    }

    @Test
    @DisplayName("audit_log is append-only: an UPDATE is refused by its trigger")
    void theHistoryIsAppendOnly() {
        UUID rowId = UUID.randomUUID();
        sql("insert into %s (table_schema, table_name, row_id, operation) values ('probe', 'probe', ?, 'INSERT')"
                .formatted(AUDIT_LOG), rowId);

        assertThatThrownBy(() -> sql("update %s set operation = 'DELETE' where row_id = ?".formatted(AUDIT_LOG), rowId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("The purge deletes rows past the retention, in batches, and keeps the newer ones")
    void thePurgeDeletesWhatIsPastTheRetention() {
        Instant pastRetention = Instant.now().minus(settings.keepFor()).minus(Duration.ofDays(1));
        List<UUID> old = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        for (UUID rowId : old) {
            sql("""
                    insert into %s (table_schema, table_name, row_id, operation, changed_at)
                    values ('probe', 'probe', ?, 'INSERT', ?)""".formatted(AUDIT_LOG), rowId, Timestamp.from(pastRetention));
        }
        UUID recent = UUID.randomUUID();
        sql("insert into %s (table_schema, table_name, row_id, operation) values ('probe', 'probe', ?, 'INSERT')"
                .formatted(AUDIT_LOG), recent);

        purgeJob.purgeOld();

        for (UUID rowId : old) {
            assertThat(historyOf("probe", rowId)).isEmpty();
        }
        assertThat(historyOf("probe", recent)).hasSize(1);
    }

    @Test
    @DisplayName("One batch deletes at most its size, so the job's loop goes on while a batch comes back full")
    void aBatchDeletesAtMostItsSize() {
        Instant pastRetention = Instant.now().minus(settings.keepFor()).minus(Duration.ofDays(1));
        for (int index = 0; index < 3; index++) {
            sql("""
                    insert into %s (table_schema, table_name, row_id, operation, changed_at)
                    values ('probe', 'probe-batch', ?, 'INSERT', ?)""".formatted(AUDIT_LOG), UUID.randomUUID(), Timestamp.from(pastRetention));
        }
        Instant cutoff = Instant.now().minus(settings.keepFor());

        assertThat(retention.deleteChangedBefore(cutoff, 2)).isEqualTo(2);
        purgeJob.purgeOld();
        assertThat(jdbc.queryForObject("select count(*) from %s where table_name = 'probe-batch'".formatted(AUDIT_LOG), Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("The purge keeps going past one batch")
    void thePurgeGoesOnPastOneBatch() {
        Instant pastRetention = Instant.now().minus(settings.keepFor()).minus(Duration.ofDays(1));
        int rows = settings.batchSize() + settings.batchSize() / 2;
        assertThat(sql("""
                insert into %s (table_schema, table_name, row_id, operation, changed_at)
                select 'probe', 'probe-batches', gen_random_uuid(), 'INSERT', ? from generate_series(1, ?)""".formatted(AUDIT_LOG),
                Timestamp.from(pastRetention), rows)).isEqualTo(rows);

        purgeJob.purgeOld();

        assertThat(jdbc.queryForObject("select count(*) from %s where table_name = 'probe-batches'".formatted(AUDIT_LOG), Integer.class))
                .isZero();
    }

    /**
     * The batch is {@code = ANY (ARRAY(...))}, an init plan computed once whatever the planner picks: as
     * {@code IN (...)} it may run again per outer row and delete more than one batch (DATA-29). That is the
     * statement's own text, so it is checked as text too.
     */
    @Test
    @DisplayName("The purge's batch is an init plan over idx_audit_log_changed_at")
    void thePurgeBatchIsPlannedOnItsIndex() {
        MapSqlParameterSource parameters = new MapSqlParameterSource("changedBefore", Timestamp.from(Instant.now()))
                .addValue("batchSize", settings.batchSize());

        String plan = QueryPlan.explain(jdbc, transactionManager, ChangeHistoryRetention.DELETE_BATCH, parameters);

        assertThat(ChangeHistoryRetention.DELETE_BATCH).contains("WHERE id = ANY (ARRAY(SELECT id").doesNotContain(" IN (SELECT");
        assertThat(plan).as(plan).contains("InitPlan").doesNotContain("Seq Scan");
        assertThat(QueryPlan.indexesScanned(plan)).as(plan).contains("idx_audit_log_changed_at");
    }

    @Test
    @DisplayName("A retention under 90 days is refused at startup, so a slip never deletes the history the next night")
    void theRetentionHasAMinimum() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertThat(validator.validate(new ChangeHistoryPurgeJob.Settings(settings.cron(), Duration.ofDays(89), 10))).hasSize(1);
            assertThat(validator.validate(new ChangeHistoryPurgeJob.Settings(settings.cron(), Duration.ofDays(90), 10))).isEmpty();
        }
    }
}
