package com.example.app.todo;

import com.example.app.support.IntegrationTest;
import com.example.app.support.ReadCost;
import com.example.app.todo.domain.TodoStatus;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every cell of the todo, todo list and todo-tag reads (each offered order under each filter, a later
 * batch or page, the ids lookups, the list's second step, a cascade) reads at most its bound of rows: the
 * matrix of crud-gen tests.md row 28. Two pages where an index serves the cell; more only where a filter
 * is decided to be applied while walking (0003's priority, the tag, the search), and then exactly what
 * that decision allows. The bound holds whichever plan the planner picks, and other classes' rows can
 * only make a plan the index doesn't serve read more: it holds alone and in a full run.
 *
 * <p>The seed makes every cell its index doesn't serve read at least ten times its bound: the tested
 * owner's 2,000 todos in 20 lists of 100 (one in twenty OPEN, the latest due; one in ten HIGH; ten tags,
 * each on one todo in ten), beside a sibling owner with twenty times as many; the owner's 520 lists beside
 * the sibling's 10,000. Rows are written as the application writes them: UUIDv7 ids and {@code created_at}
 * from the clock, in twenty rounds of the owner's share then the sibling's, so they reach the heap in id
 * order and the owners interleave. Random ids, or one block per owner, would hide a plan that walks the
 * primary key backward (cheap to the planner exactly when the heap follows the ids, as in production).
 * Plus a tie group: 200 more of the owner's todos due on one day (a busy day), with a later batch's cursor
 * inside it; and an old, big owner: 3,000 lists made before anyone else's, the scope an id order walks the
 * primary key for.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TodoIndexIntegrationTest extends IntegrationTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    // The select lists of the repositories' projections: "select id" alone could be answered from an
    // index only, a cheaper plan than the real query gets.
    private static final String TODO_SUMMARY = """
            select id, todo_list_id, title, status, priority, due_date, completed_at,
                   created_at, updated_at, created_by, updated_by, version
              from todos""";
    private static final String TODO_LIST_ROW = """
            select id, name, owner_id, created_at, updated_at, created_by, updated_by, version
              from todo_lists""";
    private static final String TAG_ROW = """
            select id, name, color, active, owner_id, created_at, updated_at, created_by, updated_by, version
              from tags""";
    private static final String DROPDOWN_ITEM = "select id, name from todo_lists";

    /** A page of the list (its default size, plus the one row that tells whether there is a next). */
    private static final int PAGE = 21;
    /** What a cell an index serves may read: a page, with room for a row or two beyond it. */
    private static final int TWO_PAGES = 2 * PAGE;
    /** The owner's todos: 20 lists of 100, plus the tie group's. */
    private static final int OWNER_TODOS = 2_000 + 200;
    /** The day the tie group's todos share: inside the seed's due dates, so the group sits mid-order. */
    private static final LocalDate BUSY_DAY = LocalDate.of(2026, 7, 1);
    private static final int OWNER_LISTS = 520;
    private static final int OLD_OWNER_LISTS = 3_000;
    /** Rounds of the seed: the owner's share, then the sibling's, each round newer than the last. */
    private static final int ROUNDS = 20;
    /** The share of the rarest value of a filter applied while walking (HIGH priority, one tag), as seeded. */
    private static final int ONE_IN = 10;
    /** A walk that filters: two pages out of every ONE_IN rows read, from the table and from a probe beside it. */
    private static final int WALK = TWO_PAGES * ONE_IN * 2;

    private final MapSqlParameterSource parameters = new MapSqlParameterSource();

    @BeforeAll
    void seedAnOwnerBesideABiggerOne() throws Exception {
        UUID ownerId = newUser().id();
        UUID siblingId = newUser().id();
        // An owner with many lists, all made before anyone else's: under an id order the planner takes the
        // primary key backward for such a scope (its share looks big, the heap follows the ids) and reads every
        // newer list first; measured 10,540 rows for a page of 20 with (owner_id, id) in place.
        UUID oldOwnerId = newUser().id();
        seedLists(oldOwnerId, OLD_OWNER_LISTS);
        for (int round = 0; round < ROUNDS; round++) {
            seedLists(ownerId, OWNER_LISTS / ROUNDS);
            seedLists(siblingId, 10_000 / ROUNDS);
        }
        // The owner's first 20 lists get 100 todos each, the sibling's first 40 a thousand.
        for (int round = 0; round < ROUNDS; round++) {
            seedTodos(ownerId, round, 1, 100);
            seedTodos(siblingId, 2 * round, 2, 1_000);
        }
        seedTieGroup(ownerId);
        // Ten tags of the owner, each on one todo in ten (a random one: tags follow no order).
        // tenancy-row:begin
        // sql("""
        //         insert into tags (id, tenant_id, owner_id, name, active, created_at, updated_at, version)
        //         select uuidv7(), ?, ?, 'Seeded ' || n, true, now(), now(), 0 from generate_series(0, 9) n
        //         """, testTenant().id(), ownerId);
        // sql("""
        //         insert into todo_tags (id, tenant_id, todo_id, tag_id, owner_id, created_at, updated_at, version)
        //         select uuidv7(), todo.tenant_id, todo.id, tag.id, todo.owner_id, now(), now(), 0
        //         from todos todo join tags tag
        //           on tag.owner_id = todo.owner_id and tag.name = 'Seeded ' || (abs(hashtext(todo.id::text)) % 10)
        //         where todo.owner_id = ?
        //         """, ownerId);
        // tenancy-row:else
        sql("""
                insert into tags (id, owner_id, name, active, created_at, updated_at, version)
                select uuidv7(), ?, 'Seeded ' || n, true, now(), now(), 0 from generate_series(0, 9) n
                """, ownerId);
        sql("""
                insert into todo_tags (id, todo_id, tag_id, owner_id, created_at, updated_at, version)
                select uuidv7(), todo.id, tag.id, todo.owner_id, now(), now(), 0
                from todos todo join tags tag
                  on tag.owner_id = todo.owner_id and tag.name = 'Seeded ' || (abs(hashtext(todo.id::text)) % 10)
                where todo.owner_id = ?
                """, ownerId);
        // tenancy-row:end
        UUID tagId = jdbc.queryForObject("select id from tags where owner_id = ? and name = 'Seeded 0'", UUID.class, ownerId);
        List<UUID> taggedTodos = jdbc.queryForList("select todo_id from todo_tags where tag_id = ? limit 21", UUID.class, tagId);
        // A later batch's cursor sits inside the owner's key range, at a seeded todo: a constant outside the
        // seeded rows leaves nothing past the seek, and the planner rightly walks another index.
        Map<String, Object> middleByDue = jdbc.queryForMap(
                "select due_sort, id from todos where owner_id = ? order by due_sort, id offset 1000 limit 1", ownerId);
        UUID middleByNewest = jdbc.queryForObject(
                "select id from todos where owner_id = ? order by created_at desc, id desc offset 1000 limit 1", UUID.class, ownerId);
        // As an OffsetDateTime: a java.sql.Timestamp would be bound in the JVM's time zone.
        OffsetDateTime middleCreated = jdbc.queryForObject("select created_at from todos where id = ?", OffsetDateTime.class, middleByNewest);
        // Inside the busy day's group: the expanded seek (due_sort >= :day and ...) rereads every row of the day before it.
        UUID insideTheTies = jdbc.queryForObject(
                "select id from todos where owner_id = ? and due_sort = ? order by id offset 150 limit 1", UUID.class, ownerId, BUSY_DAY);
        parameters.addValue("owner", ownerId)
                .addValue("list", jdbc.queryForObject("select todo_list_id from todos where owner_id = ? limit 1", UUID.class, ownerId))
                .addValue("tag", tagId)
                .addValue("todo", taggedTodos.getFirst())
                .addValue("todos", taggedTodos)
                .addValue("lists", jdbc.queryForList("select id from todo_lists where owner_id = ? limit 20", UUID.class, ownerId))
                .addValue("tags", List.of(tagId))
                .addValue("oldOwner", oldOwnerId)
                .addValue("priority", "HIGH")
                .addValue("due", LocalDate.of(2026, 2, 20))
                .addValue("seekDue", middleByDue.get("due_sort"))
                .addValue("seekId", middleByDue.get("id"))
                .addValue("seekCreated", middleCreated)
                .addValue("seekNewest", middleByNewest)
                .addValue("busyDay", BUSY_DAY)
                .addValue("insideTheTies", insideTheTies)
                .addValue("query", "matches nothing");
        // Every status value bound as a parameter, as the repository binds it.
        for (TodoStatus status : TodoStatus.values()) {
            parameters.addValue("status" + status, status.name());
        }
        // A finer sample: in a full run the tables also hold every other class's rows (other tenants' too),
        // and the default one misjudges the seeded owner's share.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("set local default_statistics_target = 1000");
            jdbc.execute("analyze todo_lists, todos, tags, todo_tags");
        });
    }

    /** {@code count} more lists of the owner, created now: UUIDv7 ids and clock times, as the application writes them. */
    private void seedLists(UUID owner, int count) {
        // tenancy-row:begin
        // // Row mode: every insert names its tenant (tenant_id has no default).
        // sql("""
        //         insert into todo_lists (id, tenant_id, owner_id, name, created_at, updated_at, version)
        //         select uuidv7(), ?, ?, 'Seeded ' || gen_random_uuid(), clock_timestamp(), now(), 0 from generate_series(1, ?) n
        //         """, testTenant().id(), owner, count);
        // tenancy-row:else
        sql("""
                insert into todo_lists (id, owner_id, name, created_at, updated_at, version)
                select uuidv7(), ?, 'Seeded ' || gen_random_uuid(), clock_timestamp(), now(), 0 from generate_series(1, ?) n
                """, owner, count);
        // tenancy-row:end
    }

    /**
     * {@code perList} todos in each of the owner's lists after the first {@code skipped}, {@code lists} of them,
     * created now, one a day: the last one in twenty days OPEN (open todos are the latest due, done ones pile up
     * from the past), one in ten HIGH and three in ten MEDIUM, every day's kinds spread over the lists.
     */
    private void seedTodos(UUID owner, int skipped, int lists, int perList) {
        // tenancy-row:begin
        // sql("""
        //         insert into todos (id, %stenant_id, todo_list_id, owner_id, title, status, priority, due_date, completed_at,
        //                            created_at, updated_at, version)
        //         select uuidv7(), %slist.tenant_id, list.id, list.owner_id, 'Seeded',
        //                case when n > ? * 0.95 then 'OPEN' else 'DONE' end,
        //                case (n + list.rank) %% 10 when 0 then 'HIGH' when 1 then 'MEDIUM' when 2 then 'MEDIUM' when 3 then 'MEDIUM'
        //                     else 'LOW' end,
        //                date '2026-01-01' + (n %% 365), case when n > ? * 0.95 then null else now() end, clock_timestamp(), now(), 0
        //         from (select id, tenant_id, owner_id, row_number() over (order by id) as rank from todo_lists where owner_id = ?) list
        //         cross join generate_series(1, ?) n
        //         where list.rank > ? and list.rank <= ?
        //         """.formatted(TODO_NUMBER_COLUMN, TODO_NUMBER), perList, perList, owner, perList, skipped, skipped + lists);
        // tenancy-row:else
        sql("""
                insert into todos (id, %stodo_list_id, owner_id, title, status, priority, due_date, completed_at,
                                   created_at, updated_at, version)
                select uuidv7(), %slist.id, list.owner_id, 'Seeded',
                       case when n > ? * 0.95 then 'OPEN' else 'DONE' end,
                       case (n + list.rank) %% 10 when 0 then 'HIGH' when 1 then 'MEDIUM' when 2 then 'MEDIUM' when 3 then 'MEDIUM'
                            else 'LOW' end,
                       date '2026-01-01' + (n %% 365), case when n > ? * 0.95 then null else now() end, clock_timestamp(), now(), 0
                from (select id, owner_id, row_number() over (order by id) as rank from todo_lists where owner_id = ?) list
                cross join generate_series(1, ?) n
                where list.rank > ? and list.rank <= ?
                """.formatted(TODO_NUMBER_COLUMN, TODO_NUMBER), perList, perList, owner, perList, skipped, skipped + lists);
        // tenancy-row:end
    }

    /** 200 done todos of the owner due on {@link #BUSY_DAY}, in one of their lists: ten pages of one sort key. */
    private void seedTieGroup(UUID owner) {
        // tenancy-row:begin
        // sql("""
        //         insert into todos (id, %stenant_id, todo_list_id, owner_id, title, status, priority, due_date, completed_at,
        //                            created_at, updated_at, version)
        //         select uuidv7(), %slist.tenant_id, list.id, list.owner_id, 'Busy',
        //                'DONE', 'LOW', ?, now(), clock_timestamp(), now(), 0
        //         from (select id, tenant_id, owner_id from todo_lists where owner_id = ? order by id limit 1) list
        //         cross join generate_series(1, 200) n
        //         """.formatted(TODO_NUMBER_COLUMN, TODO_NUMBER), BUSY_DAY, owner);
        // tenancy-row:else
        sql("""
                insert into todos (id, %stodo_list_id, owner_id, title, status, priority, due_date, completed_at,
                                   created_at, updated_at, version)
                select uuidv7(), %slist.id, list.owner_id, 'Busy',
                       'DONE', 'LOW', ?, now(), clock_timestamp(), now(), 0
                from (select id, owner_id from todo_lists where owner_id = ? order by id limit 1) list
                cross join generate_series(1, 200) n
                """.formatted(TODO_NUMBER_COLUMN, TODO_NUMBER), BUSY_DAY, owner);
        // tenancy-row:end
    }

    /** A cell: what it is, the index meant to serve it (the order's own one for a filter applied while walking), its statement, its bound. */
    private static Arguments cell(String name, String index, String statement, int bound) {
        return Arguments.of(name, index, statement, bound);
    }

    /**
     * The cells, as the repositories issue them: the same select list, predicates and order, a later batch
     * with the seek exactly as Seek.after writes it (one row-value comparison), the offset list's later page.
     */
    static Stream<Arguments> cells() {
        List<Arguments> cells = new ArrayList<>();
        cells.add(cell("todos, DUE_DATE", "idx_todos_owner_id_due_sort_id",
                TODO_SUMMARY + " where owner_id = :owner order by due_sort, id limit 21", TWO_PAGES));
        // NEWEST is the creation time, then the id: an id order would give the primary key a walk to compete with.
        cells.add(cell("todos, NEWEST", "idx_todos_owner_id_created_at_id",
                TODO_SUMMARY + " where owner_id = :owner order by created_at desc, id desc limit 21", TWO_PAGES));
        // The seek's row value starts the scan exactly at the cursor; without a bound a later batch walks from the start.
        cells.add(cell("todos, DUE_DATE, a later batch", "idx_todos_owner_id_due_sort_id",
                TODO_SUMMARY + " where owner_id = :owner and (due_sort, id) > (:seekDue, :seekId) order by due_sort, id limit 21",
                TWO_PAGES));
        // Inside a tie group: bounded on due_sort alone, the scan would reread the day's rows before the cursor on each batch.
        cells.add(cell("todos, DUE_DATE, a later batch inside a day's tie group", "idx_todos_owner_id_due_sort_id",
                TODO_SUMMARY + " where owner_id = :owner and (due_sort, id) > (:busyDay, :insideTheTies) order by due_sort, id limit 21",
                TWO_PAGES));
        cells.add(cell("todos, NEWEST, a later batch", "idx_todos_owner_id_created_at_id",
                TODO_SUMMARY + " where owner_id = :owner and (created_at, id) < (:seekCreated, :seekNewest)"
                        + " order by created_at desc, id desc limit 21", TWO_PAGES));
        cells.add(cell("todos of a list, DUE_DATE", "idx_todos_todo_list_id_owner_id_due_sort_id",
                TODO_SUMMARY + " where owner_id = :owner and todo_list_id = :list order by due_sort, id limit 21", TWO_PAGES));
        cells.add(cell("todos of a list, NEWEST", "idx_todos_todo_list_id_owner_id_created_at_id",
                TODO_SUMMARY + " where owner_id = :owner and todo_list_id = :list order by created_at desc, id desc limit 21",
                TWO_PAGES));
        // A status filter with a rare value (OPEN) scopes: an index per order, every value bound.
        for (TodoStatus status : TodoStatus.values()) {
            cells.add(cell("todos " + status + ", DUE_DATE", "idx_todos_owner_id_status_due_sort_id",
                    TODO_SUMMARY + " where owner_id = :owner and status = :status" + status + " order by due_sort, id limit 21",
                    TWO_PAGES));
            cells.add(cell("todos " + status + ", NEWEST", "idx_todos_owner_id_status_created_at_id",
                    TODO_SUMMARY + " where owner_id = :owner and status = :status" + status + " order by created_at desc, id desc limit 21",
                    TWO_PAGES));
        }
        // Priority has no rare value (0003): filtered while walking the order's index.
        cells.add(cell("todos HIGH, DUE_DATE (filtered while walking)", "idx_todos_owner_id_due_sort_id",
                TODO_SUMMARY + " where owner_id = :owner and priority = :priority order by due_sort, id limit 21", WALK));
        cells.add(cell("todos HIGH, NEWEST (filtered while walking)", "idx_todos_owner_id_created_at_id",
                TODO_SUMMARY + " where owner_id = :owner and priority = :priority order by created_at desc, id desc limit 21", WALK));
        // dueOnOrBefore is offered under DUE_DATE only (TodoFilter.isDueOnOrBeforeSortedByDueDate): a range of its index.
        cells.add(cell("todos due on or before, DUE_DATE", "idx_todos_owner_id_due_sort_id",
                TODO_SUMMARY + " where owner_id = :owner and due_sort <= :due and due_date <= :due order by due_sort, id limit 21",
                TWO_PAGES));
        // tagId: the order's index probing the pair, or the tag's pairs probing the todos; a tag is on one todo in ten.
        cells.add(cell("todos with a tag, DUE_DATE", "uq_todo_tags_todo_id_tag_id", """
                select t.id from todos t
                where t.owner_id = :owner
                  and exists (select 1 from todo_tags tt where tt.todo_id = t.id and tt.tag_id = :tag)
                order by t.due_sort, t.id limit 21""", WALK));
        cells.add(cell("todos with a tag, NEWEST", "uq_todo_tags_todo_id_tag_id", """
                select t.id from todos t
                where t.owner_id = :owner
                  and exists (select 1 from todo_tags tt where tt.todo_id = t.id and tt.tag_id = :tag)
                order by t.created_at desc, t.id desc limit 21""", WALK));
        // query is a contains search no btree serves (0003): it reads the owner's rows, never anyone else's.
        cells.add(cell("todos matching a query, DUE_DATE (the owner's todos at most)", "idx_todos_owner_id_due_sort_id",
                TODO_SUMMARY + " where owner_id = :owner and strpos(lower(title), :query) > 0 order by due_sort, id limit 21",
                OWNER_TODOS));
        cells.add(cell("todos matching a query, NEWEST (the owner's todos at most)", "idx_todos_owner_id_created_at_id",
                TODO_SUMMARY + " where owner_id = :owner and strpos(lower(title), :query) > 0 order by created_at desc, id desc limit 21",
                OWNER_TODOS));
        // The ids filter under every order: the asked rows through the primary key, then sorted.
        cells.add(cell("todos by ids, DUE_DATE (the ids filter, the plans' todo lookup)", "pk_todos",
                TODO_SUMMARY + " where owner_id = :owner and id in (:todos) order by due_sort, id limit 21", TWO_PAGES));
        cells.add(cell("todos by ids, NEWEST", "pk_todos",
                TODO_SUMMARY + " where owner_id = :owner and id in (:todos) order by created_at desc, id desc limit 21", TWO_PAGES));
        cells.add(cell("the tags of a batch (the list's second step: the pairs and their tags)", "uq_todo_tags_todo_id_tag_id", """
                select tt.todo_id, g.id, g.name from todo_tags tt join tags g on g.id = tt.tag_id
                where tt.todo_id in (:todos) order by g.id""", 2 * TWO_PAGES));
        cells.add(cell("the tags of one todo (the detail, the todo's delete)", "uq_todo_tags_todo_id_tag_id", """
                select tt.id, g.id, g.name from todo_tags tt join tags g on g.id = tt.tag_id
                where tt.todo_id = :todo order by g.id""", PAGE));
        // ON DELETE CASCADE finds the tag's pairs through the tag's foreign key index.
        cells.add(cell("a tag's delete (the cascade, run and rolled back)", "idx_todo_tags_tag_id_owner_id_todo_id",
                "delete from todo_tags where tag_id = :tag and owner_id = :owner", 2 * OWNER_TODOS / ONE_IN));
        cells.add(cell("todo lists, NAME", "idx_todo_lists_owner_id_name_id",
                TODO_LIST_ROW + " where owner_id = :owner order by name, id limit 20", TWO_PAGES));
        cells.add(cell("todo lists, NAME, a later page (the offset's rows, then the page)", "idx_todo_lists_owner_id_name_id",
                TODO_LIST_ROW + " where owner_id = :owner order by name, id limit 20 offset 40", 40 + TWO_PAGES));
        cells.add(cell("todo lists, NEWEST", "idx_todo_lists_owner_id_created_at_id",
                TODO_LIST_ROW + " where owner_id = :owner order by created_at desc, id desc limit 20", TWO_PAGES));
        cells.add(cell("todo lists matching a query (the owner's lists at most)", "idx_todo_lists_owner_id_name_id",
                TODO_LIST_ROW + " where owner_id = :owner and strpos(lower(name), :query) > 0 order by name, id limit 20",
                OWNER_LISTS));
        cells.add(cell("todo list dropdown, DESC (backwards)", "idx_todo_lists_owner_id_name_id",
                DROPDOWN_ITEM + " where owner_id = :owner order by name desc, id desc limit 21", TWO_PAGES));
        cells.add(cell("todo lists by ids, NAME", "pk_todo_lists",
                TODO_LIST_ROW + " where owner_id = :owner and id in (:lists) order by name, id limit 20", TWO_PAGES));
        cells.add(cell("todo lists by ids, NEWEST", "pk_todo_lists",
                TODO_LIST_ROW + " where owner_id = :owner and id in (:lists) order by created_at desc, id desc limit 20", TWO_PAGES));
        cells.add(cell("todo lists of an old, big owner, NEWEST (no walk of the primary key)", "idx_todo_lists_owner_id_created_at_id",
                TODO_LIST_ROW + " where owner_id = :oldOwner order by created_at desc, id desc limit 20", TWO_PAGES));
        cells.add(cell("todo lists, NEWEST, a later page", "idx_todo_lists_owner_id_created_at_id",
                TODO_LIST_ROW + " where owner_id = :owner order by created_at desc, id desc limit 20 offset 40", 40 + TWO_PAGES));
        cells.add(cell("tags by ids", "pk_tags",
                TAG_ROW + " where owner_id = :owner and id in (:tags) order by name, id limit 20", TWO_PAGES));
        return cells.stream();
    }

    /**
     * Two checks, neither of which turns on statistics: the index meant for the cell exists (a dropped or
     * never-created one fails here, even where another index happens to serve the seed cheaply enough), and
     * what the statement reads stays within the cell's bound.
     */
    @ParameterizedTest(name = "{0} ({1}): at most {3} rows")
    @MethodSource("cells")
    @DisplayName("Every cell of the todo reads (order x filter, later batches, lookups) has its index and reads at most its bound")
    void everyCellReadsAtMostItsBound(String cell, String index, String statement, int bound) {
        ReadCost cost = ReadCost.of(jdbc, transactionManager, statement, parameters);

        assertThat(ReadCost.indexExists(jdbc, index)).as(index).isTrue();
        assertThat(cost.rowsRead()).as(cost.plan()).isLessThanOrEqualTo(bound);
    }
}
