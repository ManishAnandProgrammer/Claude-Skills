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
 * Every list order, under every filter that scopes it, reads an index in order: the scope in the
 * index condition and no sort step. The statements have the shape of the repositories' queries.
 * The planner chooses by statistics, so one owner gets a realistic spread first (many lists, few
 * open todos) and the tables are analysed; scans that read whole tables are switched off, because
 * on the few rows of a test database they would win on cost and hide a missing index.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TodoIndexIntegrationTest extends IntegrationTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID ownerId;
    private UUID listId;

    @BeforeAll
    void seedOneOwnerWithManyTodos() throws Exception {
        ownerId = newUser().id();
        sql("""
                insert into todo_lists (id, owner_id, name, created_at, updated_at, version)
                select gen_random_uuid(), ?, 'Seeded ' || n, now(), now(), 0 from generate_series(1, 50) n
                """, ownerId);
        sql("""
                insert into todos (id, todo_list_id, owner_id, title, status, priority, due_date, completed_at,
                                   created_at, updated_at, version)
                select gen_random_uuid(), list.id, list.owner_id, 'Seeded',
                       case when n % 20 = 0 then 'OPEN' else 'DONE' end, 'LOW', date '2026-01-01' + n,
                       case when n % 20 = 0 then null else now() end, now(), now(), 0
                from todo_lists list cross join generate_series(1, 100) n
                where list.owner_id = ?
                """, ownerId);
        listId = jdbc.queryForObject("select id from todo_lists where owner_id = ? limit 1", UUID.class, ownerId);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> jdbc.execute("analyze todo_lists, todos"));
    }

    static Stream<Arguments> scopedOrders() {
        return Stream.of(
                Arguments.of("todos, DUE_DATE", "todos where owner_id = :owner order by due_sort, id",
                        List.of("owner_id")),
                Arguments.of("todos, NEWEST", "todos where owner_id = :owner order by id desc",
                        List.of("owner_id")),
                Arguments.of("todos of a list, DUE_DATE",
                        "todos where owner_id = :owner and todo_list_id = :list order by due_sort, id",
                        List.of("owner_id", "todo_list_id")),
                Arguments.of("todos of a list, NEWEST",
                        "todos where owner_id = :owner and todo_list_id = :list order by id desc",
                        List.of("owner_id", "todo_list_id")),
                Arguments.of("todos in a status, DUE_DATE",
                        "todos where owner_id = :owner and status = :status order by due_sort, id",
                        List.of("owner_id", "status")),
                Arguments.of("todos in a status, NEWEST",
                        "todos where owner_id = :owner and status = :status order by id desc",
                        List.of("owner_id", "status")),
                Arguments.of("todos due on or before, DUE_DATE",
                        "todos where owner_id = :owner and due_sort <= :due and due_date <= :due order by due_sort, id",
                        List.of("owner_id", "due_sort")),
                Arguments.of("todo lists, NAME", "todo_lists where owner_id = :owner order by name, id",
                        List.of("owner_id")),
                Arguments.of("todo lists, NEWEST", "todo_lists where owner_id = :owner order by id desc",
                        List.of("owner_id")),
                Arguments.of("todo list dropdown, DESC", "todo_lists where owner_id = :owner order by name desc, id desc",
                        List.of("owner_id")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scopedOrders")
    @DisplayName("Every offered order under every scoping filter reads an index in order")
    void everyScopedOrderReadsAnIndexInOrder(String name, String fromWhereOrder, List<String> scope) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("owner", ownerId)
                .addValue("list", listId)
                .addValue("status", "OPEN")
                .addValue("due", LocalDate.of(2026, 12, 31));

        List<String> planLines = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.execute("set local enable_seqscan = off");
            jdbc.execute("set local enable_bitmapscan = off");
            return new NamedParameterJdbcTemplate(jdbc)
                    .queryForList("explain select id from " + fromWhereOrder + " limit 21", parameters, String.class);
        });
        String plan = String.join("\n", planLines);

        assertThat(plan).as(plan).contains("Index").doesNotContain("Sort");
        String indexCondition = plan.lines().filter(line -> line.contains("Index Cond:")).findFirst().orElse("");
        assertThat(indexCondition).as(plan).contains(scope);
    }
}
