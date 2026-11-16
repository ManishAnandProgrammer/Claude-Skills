package com.example.app.todo;

import com.example.app.support.IntegrationTest;
import com.example.app.support.ReadCost;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every cell of a todo's time entries (both orders, each range filter under each order, billable, a later
 * batch, the ids lookup) has its index and reads at most its bound (crud-gen tests.md row 28). The owner's
 * 50 todos with 100 entries each: a cell its index doesn't serve reads another todo's entries too, or sorts
 * the todo's whole set.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TimeEntryIndexIntegrationTest extends IntegrationTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static final String ENTRY_ROW = """
            select id, todo_id, owner_id, minutes, spent_on, note, billable, created_at, updated_at, created_by, updated_by, version
              from time_entries""";
    private static final int PAGE = 21;
    private static final int TWO_PAGES = 2 * PAGE;
    /** The entries of one todo, as seeded: what a filter left to the walk may read at most. */
    private static final int TODO_ENTRIES = 100;

    private final MapSqlParameterSource parameters = new MapSqlParameterSource();

    @BeforeAll
    void seedOneOwnerWithManyEntries() throws Exception {
        UUID ownerId = newUser().id();
        UUID listId = UUID.randomUUID();
        // tenancy-row:begin
        // // Row mode: every insert names its tenant (tenant_id has no default).
        // sql("""
        //         insert into todo_lists (id, tenant_id, owner_id, name, created_at, updated_at, version)
        //         values (?, ?, ?, 'Seeded time', now(), now(), 0)
        //         """, listId, testTenant().id(), ownerId);
        // sql("""
        //         insert into todos (id, %stenant_id, todo_list_id, owner_id, title, status, priority, created_at, updated_at, version)
        //         select gen_random_uuid(), %s?, ?, ?, 'Seeded ' || n, 'OPEN', 'LOW', now(), now(), 0 from generate_series(1, 50) n
        //         """.formatted(TODO_NUMBER_COLUMN, TODO_NUMBER), testTenant().id(), listId, ownerId);
        // tenancy-row:else
        sql("""
                insert into todo_lists (id, owner_id, name, created_at, updated_at, version)
                values (?, ?, 'Seeded time', now(), now(), 0)
                """, listId, ownerId);
        sql("""
                insert into todos (id, %stodo_list_id, owner_id, title, status, priority, created_at, updated_at, version)
                select gen_random_uuid(), %s?, ?, 'Seeded ' || n, 'OPEN', 'LOW', now(), now(), 0 from generate_series(1, 50) n
                """.formatted(TODO_NUMBER_COLUMN, TODO_NUMBER), listId, ownerId);
        // tenancy-row:end
        // tenancy-row:begin
        // sql("""
        //         insert into time_entries (id, tenant_id, todo_id, owner_id, minutes, spent_on, billable, created_at, updated_at, version)
        //         select gen_random_uuid(), todo.tenant_id, todo.id, todo.owner_id, 1 + n % 600, date '2026-01-01' + n, n % 2 = 0,
        //                now(), now(), 0
        //         from todos todo cross join generate_series(1, 100) n
        //         where todo.owner_id = ?
        //         """, ownerId);
        // tenancy-row:else
        sql("""
                insert into time_entries (id, todo_id, owner_id, minutes, spent_on, billable, created_at, updated_at, version)
                select gen_random_uuid(), todo.id, todo.owner_id, 1 + n % 600, date '2026-01-01' + n, n % 2 = 0,
                       now(), now(), 0
                from todos todo cross join generate_series(1, 100) n
                where todo.owner_id = ?
                """, ownerId);
        // tenancy-row:end
        UUID todoId = jdbc.queryForObject("select id from todos where owner_id = ? limit 1", UUID.class, ownerId);
        Map<String, Object> middle = jdbc.queryForMap(
                "select spent_on, id from time_entries where todo_id = ? order by spent_on desc, id desc offset 50 limit 1", todoId);
        parameters.addValue("owner", ownerId)
                .addValue("todo", todoId)
                .addValue("seekDay", middle.get("spent_on"))
                .addValue("seekId", middle.get("id"))
                .addValue("from", LocalDate.of(2026, 2, 1))
                .addValue("to", LocalDate.of(2026, 3, 1))
                .addValue("min", 30)
                .addValue("max", 120)
                .addValue("billable", true)
                .addValue("ids", jdbc.queryForList("select id from time_entries where todo_id = ? limit 21", UUID.class, todoId));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("set local default_statistics_target = 1000");
            jdbc.execute("analyze todos, time_entries");
        });
    }

    private static Arguments cell(String name, String index, String statement, int bound) {
        return Arguments.of(name, index, statement, bound);
    }

    /**
     * The list is always one todo's entries (the path scopes it): each order has its index, and a range is
     * a range of its own order's index. The other filters (the other order's range, billable) are filtered
     * while walking the todo's entries (0005: one todo's entries are few), bounded by them.
     */
    static Stream<Arguments> cells() {
        String scope = ENTRY_ROW + " where todo_id = :todo and owner_id = :owner";
        return Stream.of(
                cell("SPENT_ON", "idx_time_entries_todo_id_owner_id_spent_on_id",
                        scope + " order by spent_on desc, id desc limit 21", TWO_PAGES),
                cell("MINUTES", "idx_time_entries_todo_id_owner_id_minutes_id",
                        scope + " order by minutes desc, id desc limit 21", TWO_PAGES),
                cell("SPENT_ON, a later batch", "idx_time_entries_todo_id_owner_id_spent_on_id",
                        scope + " and (spent_on, id) < (:seekDay, :seekId) order by spent_on desc, id desc limit 21", TWO_PAGES),
                cell("SPENT_ON within spentOnFrom/To (a range of its index)", "idx_time_entries_todo_id_owner_id_spent_on_id",
                        scope + " and spent_on >= :from and spent_on <= :to order by spent_on desc, id desc limit 21", TWO_PAGES),
                cell("MINUTES within minMinutes/maxMinutes (a range of its index)", "idx_time_entries_todo_id_owner_id_minutes_id",
                        scope + " and minutes >= :min and minutes <= :max order by minutes desc, id desc limit 21", TWO_PAGES),
                cell("MINUTES within spentOnFrom/To (the todo's entries at most)", "idx_time_entries_todo_id_owner_id_minutes_id",
                        scope + " and spent_on >= :from and spent_on <= :to order by minutes desc, id desc limit 21", TODO_ENTRIES),
                cell("SPENT_ON within minMinutes/maxMinutes (the todo's entries at most)", "idx_time_entries_todo_id_owner_id_spent_on_id",
                        scope + " and minutes >= :min and minutes <= :max order by spent_on desc, id desc limit 21", TODO_ENTRIES),
                cell("SPENT_ON, billable (the todo's entries at most)", "idx_time_entries_todo_id_owner_id_spent_on_id",
                        scope + " and billable = :billable order by spent_on desc, id desc limit 21", TODO_ENTRIES),
                cell("MINUTES, billable (the todo's entries at most)", "idx_time_entries_todo_id_owner_id_minutes_id",
                        scope + " and billable = :billable order by minutes desc, id desc limit 21", TODO_ENTRIES),
                // The ids filter under every order: the asked rows through the primary key, then sorted.
                cell("by ids, SPENT_ON", "pk_time_entries", scope + " and id in (:ids) order by spent_on desc, id desc limit 21", TWO_PAGES),
                cell("by ids, MINUTES", "pk_time_entries", scope + " and id in (:ids) order by minutes desc, id desc limit 21", TWO_PAGES));
    }

    @ParameterizedTest(name = "{0} ({1}): at most {3} rows")
    @MethodSource("cells")
    @DisplayName("Every cell of a todo's time entries has its index and reads at most its bound")
    void everyCellReadsAtMostItsBound(String cell, String index, String statement, int bound) {
        ReadCost cost = ReadCost.of(jdbc, transactionManager, statement, parameters);

        assertThat(ReadCost.indexExists(jdbc, index)).as(index).isTrue();
        assertThat(cost.rowsRead()).as(cost.plan()).isLessThanOrEqualTo(bound);
    }
}
