package com.example.app.planning;

import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every plan order, under every filter that scopes it, reads its intended index in order (by name, the
 * scope in the index condition, no sort step), with the statements PlanRepositoryImpl issues: the first
 * batch and a later one, whose seek is {@code starts_on > ? or (starts_on = ? and id > ?)}. One owner
 * gets many plans over many todos, the tables are analysed, and whole-table scans are switched off,
 * because on a test database's few rows they would win on cost and hide a missing index.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlanIndexIntegrationTest extends IntegrationTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID ownerId;
    private UUID todoId;
    private List<UUID> planIds;

    @BeforeAll
    void seedOneOwnerWithManyPlans() throws Exception {
        ownerId = newUser().id();
        sql("""
                insert into todo_lists (id, owner_id, name, created_at, updated_at, version)
                values (gen_random_uuid(), ?, 'Seeded', now(), now(), 0)
                """, ownerId);
        sql("""
                insert into todos (id, todo_list_id, owner_id, title, status, priority, created_at, updated_at, version)
                select gen_random_uuid(), list.id, list.owner_id, 'Seeded ' || n, 'OPEN', 'LOW', now(), now(), 0
                from todo_lists list cross join generate_series(1, 50) n
                where list.owner_id = ?
                """, ownerId);
        // One plan a day, each todo every 50th day: one owner's plans never share a day.
        sql("""
                insert into plans (id, todo_id, owner_id, starts_on, ends_on, created_at, updated_at, version)
                select gen_random_uuid(), todo.id, todo.owner_id, date '2020-01-01' + (n * 50 + todo.rank),
                       date '2020-01-01' + (n * 50 + todo.rank), now(), now(), 0
                from (select id, owner_id, (row_number() over (order by id) - 1)::integer as rank from todos where owner_id = ?) todo
                cross join generate_series(0, 39) n
                """, ownerId);
        todoId = jdbc.queryForObject("select todo_id from plans where owner_id = ? limit 1", UUID.class, ownerId);
        planIds = jdbc.queryForList("select id from plans where owner_id = ? limit 21", UUID.class, ownerId);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> jdbc.execute("analyze todos, plans"));
    }

    static Stream<Arguments> scopedOrders() {
        String afterStartsOn = " and (starts_on > :day or (starts_on = :day and id > :id))";
        return Stream.of(
                Arguments.of("plans, STARTS_ON", "plans where owner_id = :owner order by starts_on, id",
                        List.of("owner_id"), "idx_plans_owner_id_starts_on_id"),
                Arguments.of("plans, STARTS_ON, a later batch", "plans where owner_id = :owner" + afterStartsOn + " order by starts_on, id",
                        List.of("owner_id"), "idx_plans_owner_id_starts_on_id"),
                Arguments.of("plans, NEWEST", "plans where owner_id = :owner order by id desc",
                        List.of("owner_id"), "idx_plans_owner_id_id"),
                Arguments.of("plans, NEWEST, a later batch", "plans where owner_id = :owner and id < :id order by id desc",
                        List.of("owner_id"), "idx_plans_owner_id_id"),
                Arguments.of("plans of a todo, STARTS_ON", "plans where owner_id = :owner and todo_id = :todo order by starts_on, id",
                        List.of("owner_id", "todo_id"), "idx_plans_todo_id_owner_id_starts_on_id"),
                Arguments.of("plans of a todo, NEWEST", "plans where owner_id = :owner and todo_id = :todo order by id desc",
                        List.of("owner_id", "todo_id"), "idx_plans_todo_id_owner_id_id"));
    }

    @ParameterizedTest(name = "{0}: {3}")
    @MethodSource("scopedOrders")
    @DisplayName("Every offered order under every scoping filter reads its intended index in order")
    void everyScopedOrderReadsAnIndexInOrder(String name, String fromWhereOrder, List<String> scope, String index) {
        String plan = explain("select id from " + fromWhereOrder + " limit 21");

        assertThat(plan).as(plan).contains("using " + index + " on").doesNotContain("Sort");
        String indexCondition = plan.lines().filter(line -> line.contains("Index Cond:")).findFirst().orElse("");
        assertThat(indexCondition).as(plan).contains(scope);
    }

    /** Index names as patterns: either of a todo's two indexes serves its foreign key's check. */
    static Stream<Arguments> otherShapes() {
        return Stream.of(
                Arguments.of("a todo's delete (the foreign key's check)", "select 1 from plans where todo_id = :todo and owner_id = :owner",
                        "idx_plans_todo_id_owner_id_(starts_on_)?id"),
                // tenancy-row:begin
                // // Row mode: && isn't leakproof, so it can't run before the tenant policy and the constraint's GiST
                // // index can't take it (house-review TEN-36): the owner's plans in the tenant are read through a btree
                // // index and filtered. The constraint itself still decides (key checks don't pass the policy).
                // Arguments.of("an overlap check (a filter: && isn't leakproof)", """
                //         select 1 from plans
                //         where owner_id = :owner and daterange(starts_on, ends_on, '[]') && daterange(:day, :day, '[]')""",
                //         "idx_plans_(todo_id_)?owner_id_\\w+"));
                // tenancy-row:else
                Arguments.of("an overlap check (the exclusion constraint)", """
                        select 1 from plans
                        where owner_id = :owner and daterange(starts_on, ends_on, '[]') && daterange(:day, :day, '[]')""",
                        "ex_plans_owner_id_dates"));
                // tenancy-row:end
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("otherShapes")
    @DisplayName("The foreign key's check and the overlap check read their indexes, never the whole table")
    void everyOtherShapeReadsItsIndex(String name, String statement, String index) {
        String plan = explain(statement);

        assertThat(plan).as(plan).containsPattern("using " + index + " on").doesNotContain("Seq Scan");
    }

    /** At most a batch of ids: probed by id (the primary key or {@code (owner_id, id)}), then sorted. */
    @Test
    @DisplayName("The ids lookup probes an index by id, never the whole table")
    void idsLookupProbesAnIndexById() {
        String plan = explain("select id from plans where owner_id = :owner and id in (:ids) order by starts_on, id limit 21");

        String indexCondition = plan.lines().filter(line -> line.contains("Index Cond:")).findFirst().orElse("");
        assertThat(indexCondition).as(plan).containsPattern("\\(id = ");
        assertThat(plan).as(plan).doesNotContain("Seq Scan");
    }

    private String explain(String statement) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("owner", ownerId)
                .addValue("todo", todoId)
                .addValue("ids", planIds)
                .addValue("id", planIds.getFirst())
                .addValue("day", LocalDate.of(2021, 6, 1));
        List<String> planLines = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.execute("set local enable_seqscan = off");
            jdbc.execute("set local enable_bitmapscan = off");
            return new NamedParameterJdbcTemplate(jdbc).queryForList("explain " + statement, parameters, String.class);
        });
        return String.join("\n", planLines);
    }
}
