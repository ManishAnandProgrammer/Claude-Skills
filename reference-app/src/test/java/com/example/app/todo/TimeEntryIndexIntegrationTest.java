package com.example.app.todo;

import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
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
 * Both orders of a todo's time entries, plain and under their range filter, read their intended
 * index in order: that index named in the plan, the todo and owner in the index condition, and no
 * sort step. One owner gets many todos with many entries first and the tables are analysed; scans
 * that read whole tables are switched off.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TimeEntryIndexIntegrationTest extends IntegrationTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID ownerId;
    private UUID todoId;

    @BeforeAll
    void seedOneOwnerWithManyEntries() throws Exception {
        ownerId = newUser().id();
        UUID listId = UUID.randomUUID();
        sql("""
                insert into todo_lists (id, owner_id, name, created_at, updated_at, version)
                values (?, ?, 'Seeded time', now(), now(), 0)
                """, listId, ownerId);
        sql("""
                insert into todos (id, todo_list_id, owner_id, title, status, priority, created_at, updated_at, version)
                select gen_random_uuid(), ?, ?, 'Seeded ' || n, 'OPEN', 'LOW', now(), now(), 0 from generate_series(1, 50) n
                """, listId, ownerId);
        sql("""
                insert into time_entries (id, todo_id, owner_id, minutes, spent_on, billable, created_at, updated_at, version)
                select gen_random_uuid(), todo.id, todo.owner_id, 1 + n % 600, date '2026-01-01' + n, n % 2 = 0,
                       now(), now(), 0
                from todos todo cross join generate_series(1, 100) n
                where todo.owner_id = ?
                """, ownerId);
        todoId = jdbc.queryForObject("select id from todos where owner_id = ? limit 1", UUID.class, ownerId);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> jdbc.execute("analyze todos, time_entries"));
    }

    static Stream<Arguments> scopedOrders() {
        String scope = "time_entries where todo_id = :todo and owner_id = :owner";
        return Stream.of(
                Arguments.of("SPENT_ON", scope + " order by spent_on desc, id desc", List.of("todo_id", "owner_id"),
                        "idx_time_entries_todo_id_owner_id_spent_on_id"),
                Arguments.of("MINUTES", scope + " order by minutes desc, id desc", List.of("todo_id", "owner_id"),
                        "idx_time_entries_todo_id_owner_id_minutes_id"),
                Arguments.of("SPENT_ON within spentOnFrom/To",
                        scope + " and spent_on >= :from and spent_on <= :to order by spent_on desc, id desc",
                        List.of("todo_id", "owner_id", "spent_on"),
                        "idx_time_entries_todo_id_owner_id_spent_on_id"),
                Arguments.of("MINUTES within minMinutes/maxMinutes",
                        scope + " and minutes >= :min and minutes <= :max order by minutes desc, id desc",
                        List.of("todo_id", "owner_id", "minutes"),
                        "idx_time_entries_todo_id_owner_id_minutes_id"));
    }

    @ParameterizedTest(name = "{0}: {3}")
    @MethodSource("scopedOrders")
    @DisplayName("Every offered time entry order, plain and within its range filter, reads its intended index in order")
    void everyScopedOrderReadsAnIndexInOrder(String name, String fromWhereOrder, List<String> scope, String index) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("owner", ownerId)
                .addValue("todo", todoId)
                .addValue("from", LocalDate.of(2026, 2, 1))
                .addValue("to", LocalDate.of(2026, 3, 1))
                .addValue("min", 30)
                .addValue("max", 120);

        List<String> planLines = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.execute("set local enable_seqscan = off");
            jdbc.execute("set local enable_bitmapscan = off");
            return new NamedParameterJdbcTemplate(jdbc)
                    .queryForList("explain select id from " + fromWhereOrder + " limit 21", parameters, String.class);
        });
        String plan = String.join("\n", planLines);

        assertThat(plan).as(plan).contains("using " + index + " on").doesNotContain("Sort");
        String indexCondition = plan.lines().filter(line -> line.contains("Index Cond:")).findFirst().orElse("");
        assertThat(indexCondition).as(plan).contains(scope);
    }
}
