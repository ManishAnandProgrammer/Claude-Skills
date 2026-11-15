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
 * Every list order, under every filter that scopes it, reads its intended index in order: that index
 * named in the plan (another one that happens to avoid the sort would hide a dropped or unused
 * index), the scope in the index condition and no sort step. The statements have the shape of the repositories' queries.
 * The planner chooses by statistics, so one owner gets a realistic spread first (many lists, few
 * open todos) and the tables are analysed; scans that read whole tables are switched off, because
 * on the few rows of a test database they would win on cost and hide a missing index.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TodoIndexIntegrationTest extends IntegrationTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** The id of a later batch's cursor; any value gives the plan its shape. */
    private static final UUID SEEK_POSITION = UUID.fromString("01900000-0000-7000-8000-000000000000");

    private UUID ownerId;
    private UUID listId;
    private UUID tagId;
    private List<UUID> taggedTodos;

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
        // Other owners' lists, as in production: without them the owner holds most of the table, and
        // walking the primary key backwards with a filter can look as cheap as the owner's index.
        sql("""
                insert into todo_lists (id, owner_id, name, created_at, updated_at, version)
                select gen_random_uuid(), ?, 'Someone else ' || n, now(), now(), 0 from generate_series(1, 2000) n
                """, newUser().id());
        // Ten tags, each on one todo in ten.
        sql("""
                insert into tags (id, owner_id, name, active, created_at, updated_at, version)
                select gen_random_uuid(), ?, 'Seeded ' || n, true, now(), now(), 0 from generate_series(0, 9) n
                """, ownerId);
        sql("""
                insert into todo_tags (id, todo_id, tag_id, owner_id, created_at, updated_at, version)
                select gen_random_uuid(), todo.id, tag.id, todo.owner_id, now(), now(), 0
                from todos todo join tags tag
                  on tag.owner_id = todo.owner_id and tag.name = 'Seeded ' || ((todo.due_date - date '2026-01-01') % 10)
                where todo.owner_id = ?
                """, ownerId);
        tagId = jdbc.queryForObject("select id from tags where owner_id = ? and name = 'Seeded 0'", UUID.class, ownerId);
        taggedTodos = jdbc.queryForList("select todo_id from todo_tags where tag_id = ? limit 21", UUID.class, tagId);
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> jdbc.execute("analyze todo_lists, todos, tags, todo_tags"));
    }

    static Stream<Arguments> scopedOrders() {
        return Stream.of(
                Arguments.of("todos, DUE_DATE", "todos where owner_id = :owner order by due_sort, id",
                        List.of("owner_id"),
                        "idx_todos_owner_id_due_sort_id"),
                Arguments.of("todos, NEWEST", "todos where owner_id = :owner order by id desc",
                        List.of("owner_id"),
                        "idx_todos_owner_id_id"),
                // A later batch adds the cursor's seek (Seek.after, TodoRepositoryImpl.seekAfter): the same
                // index gives the order, and the seek's leading bound starts the scan at the cursor.
                Arguments.of("todos, DUE_DATE, a later batch", """
                        todos where owner_id = :owner
                          and due_sort >= :due and (due_sort > :due or (due_sort = :due and id > :after))
                        order by due_sort, id""",
                        List.of("owner_id", "due_sort"),
                        "idx_todos_owner_id_due_sort_id"),
                Arguments.of("todos, NEWEST, a later batch", "todos where owner_id = :owner and id < :after order by id desc",
                        List.of("owner_id"),
                        "idx_todos_owner_id_id"),
                Arguments.of("todos of a list, DUE_DATE",
                        "todos where owner_id = :owner and todo_list_id = :list order by due_sort, id",
                        List.of("owner_id", "todo_list_id"),
                        "idx_todos_todo_list_id_owner_id_due_sort_id"),
                Arguments.of("todos of a list, NEWEST",
                        "todos where owner_id = :owner and todo_list_id = :list order by id desc",
                        List.of("owner_id", "todo_list_id"),
                        "idx_todos_todo_list_id_owner_id_id"),
                Arguments.of("todos in a status, DUE_DATE",
                        "todos where owner_id = :owner and status = :status order by due_sort, id",
                        List.of("owner_id", "status"),
                        "idx_todos_owner_id_status_due_sort_id"),
                Arguments.of("todos in a status, NEWEST",
                        "todos where owner_id = :owner and status = :status order by id desc",
                        List.of("owner_id", "status"),
                        "idx_todos_owner_id_status_id"),
                Arguments.of("todos due on or before, DUE_DATE",
                        "todos where owner_id = :owner and due_sort <= :due and due_date <= :due order by due_sort, id",
                        List.of("owner_id", "due_sort"),
                        "idx_todos_owner_id_due_sort_id"),
                Arguments.of("todo lists, NAME", "todo_lists where owner_id = :owner order by name, id",
                        List.of("owner_id"),
                        "idx_todo_lists_owner_id_name_id"),
                Arguments.of("todo lists, NEWEST", "todo_lists where owner_id = :owner order by id desc",
                        List.of("owner_id"),
                        "idx_todo_lists_owner_id_id"),
                Arguments.of("todo list dropdown, DESC", "todo_lists where owner_id = :owner order by name desc, id desc",
                        List.of("owner_id"),
                        "idx_todo_lists_owner_id_name_id"));
    }

    @ParameterizedTest(name = "{0}: {3}")
    @MethodSource("scopedOrders")
    @DisplayName("Every offered order under every scoping filter reads its intended index in order")
    void everyScopedOrderReadsAnIndexInOrder(String name, String fromWhereOrder, List<String> scope, String index) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("owner", ownerId)
                .addValue("list", listId)
                .addValue("status", "OPEN")
                .addValue("due", LocalDate.of(2026, 12, 31))
                .addValue("after", SEEK_POSITION);

        List<String> planLines = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.execute("set local enable_seqscan = off");
            jdbc.execute("set local enable_bitmapscan = off");
            return new NamedParameterJdbcTemplate(jdbc)
                    .queryForList("explain select id from " + fromWhereOrder + " limit 21", parameters, String.class);
        });
        String plan = String.join("\n", planLines);

        assertThat(plan).as(plan).contains("using " + index + " on").doesNotContain("Sort");
        String indexCondition = plan.lines().filter(line -> line.contains("Index Cond:")).findFirst().orElse("");
        // One column at a time: an unscoped order (a whole shared table) has none, and contains() refuses an empty list.
        for (String column : scope) {
            assertThat(indexCondition).as(plan).contains(column);
        }
    }

    static Stream<Arguments> tagShapes() {
        return Stream.of(
                Arguments.of("todos with a tag, DUE_DATE (the order's index, probing the pair)", """
                        select t.id from todos t
                        where t.owner_id = :owner
                          and exists (select 1 from todo_tags tt where tt.todo_id = t.id and tt.tag_id = :tag)
                        order by t.due_sort, t.id limit 21""",
                        List.of("idx_todos_owner_id_due_sort_id", "uq_todo_tags_todo_id_tag_id")),
                Arguments.of("todos with a tag, NEWEST (the order's index, probing the pair)", """
                        select t.id from todos t
                        where t.owner_id = :owner
                          and exists (select 1 from todo_tags tt where tt.todo_id = t.id and tt.tag_id = :tag)
                        order by t.id desc limit 21""",
                        List.of("idx_todos_owner_id_id", "uq_todo_tags_todo_id_tag_id")),
                Arguments.of("the tags of a batch (the list's second step)", """
                        select tt.todo_id, g.id, g.name from todo_tags tt join tags g on g.id = tt.tag_id
                        where tt.todo_id in (:todos) order by g.id""",
                        List.of("uq_todo_tags_todo_id_tag_id")),
                Arguments.of("the tags of one todo (the detail, the todo's delete)", """
                        select tt.id, g.id, g.name from todo_tags tt join tags g on g.id = tt.tag_id
                        where tt.todo_id = :todo order by g.id""",
                        List.of("uq_todo_tags_todo_id_tag_id")),
                // PostgreSQL 18 can also skip-scan the pair (todo_id, tag_id) for one tag, and on the seeded
                // rows the two cost about the same, so either may win; both keep the cascade off a full scan.
                Arguments.of("a tag's delete (the cascade)", "delete from todo_tags where tag_id = :tag and owner_id = :owner",
                        List.of("(idx_todo_tags_tag_id_owner_id_todo_id|uq_todo_tags_todo_id_tag_id)")));
    }

    /**
     * Every read of the join table goes through one of its two indexes, the one each shape needs: the
     * pair leading with todo_id (the probe of the tagId filter, the tags of a batch or of one todo) or
     * the tag's foreign key (the cascade of a tag's delete). Under tagId the list still walks its order's
     * index; the join table has no order of its own.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("tagShapes")
    @DisplayName("Every todo_tags shape reads its intended indexes, never the whole table")
    void everyTagShapeReadsItsIndexes(String name, String statement, List<String> indexes) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("owner", ownerId)
                .addValue("tag", tagId)
                .addValue("todo", taggedTodos.getFirst())
                .addValue("todos", taggedTodos);

        List<String> planLines = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.execute("set local enable_seqscan = off");
            jdbc.execute("set local enable_bitmapscan = off");
            return new NamedParameterJdbcTemplate(jdbc).queryForList("explain " + statement, parameters, String.class);
        });
        String plan = String.join("\n", planLines);

        for (String index : indexes) {
            assertThat(plan).as(plan).containsPattern("using " + index + " on");
        }
        assertThat(plan).as(plan).doesNotContain("Seq Scan");
    }

    static Stream<Arguments> idsLookups() {
        return Stream.of(
                Arguments.of("todos by ids (the ids filter, the plans' todo lookup)",
                        "select id from todos where owner_id = :owner and id in (:todos) order by due_sort, id"),
                Arguments.of("todo lists by ids", "select id from todo_lists where owner_id = :owner and id in (:lists) order by name, id"),
                Arguments.of("tags by ids", "select id from tags where owner_id = :owner and id in (:tags) order by name, id"));
    }

    /**
     * The ids filter probes an index by id, whatever the order (the primary key or the owner's
     * {@code (owner_id, id)}): at most a batch of ids, so sorting those few rows afterwards is cheap,
     * while the order's index would walk all the owner's rows.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("idsLookups")
    @DisplayName("Every ids lookup probes an index by id, never the whole table")
    void everyIdsLookupProbesAnIndexById(String name, String statement) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("owner", ownerId)
                .addValue("todos", taggedTodos)
                .addValue("lists", List.of(listId))
                .addValue("tags", List.of(tagId));

        List<String> planLines = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.execute("set local enable_seqscan = off");
            jdbc.execute("set local enable_bitmapscan = off");
            return new NamedParameterJdbcTemplate(jdbc).queryForList("explain " + statement, parameters, String.class);
        });
        String plan = String.join("\n", planLines);

        String indexCondition = plan.lines().filter(line -> line.contains("Index Cond:")).findFirst().orElse("");
        assertThat(indexCondition).as(plan).containsPattern("\\(id = ");
        assertThat(plan).as(plan).doesNotContain("Seq Scan");
    }
}
