package com.example.app.todo;

import com.example.app.support.IntegrationTest;
import com.example.app.support.QueryPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class TodoIndexIntegrationTest extends IntegrationTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    // The select lists of the repositories' projections: "select id" alone could be answered from an
    // index only, a plan the real query never gets.
    private static final String TODO_SUMMARY = """
            select id, todo_list_id, title, status, priority, due_date, completed_at,
                   created_at, updated_at, created_by, updated_by, version
              from todos""";
    private static final String TODO_LIST_ROW = """
            select id, name, owner_id, created_at, updated_at, created_by, updated_by, version
              from todo_lists""";
    private static final String DROPDOWN_ITEM = "select id, name from todo_lists";
    private static final String TODO_DROPDOWN_ITEM = "select id, title from todos";
    // tenancy-row:begin
    // // The repository's query names the tenant (Hibernate's @TenantId adds it), so the statement written as
    // // the repository issues it does too.
    // private static final String AND_TENANT = " and tenant_id = :tenantId";
    // tenancy-row:else
    private static final String AND_TENANT = "";
    // tenancy-row:end

    static Stream<Arguments> offeredFiltersAndOrders() {
        return Stream.of(
                Arguments.of("todos, DUE_DATE", "idx_todos_owner_id_due_sort_id"),
                Arguments.of("todos, NEWEST", "idx_todos_owner_id_created_at_id"),
                Arguments.of("todos of a list, DUE_DATE (and the list's delete: its todos' key check)",
                        "idx_todos_todo_list_id_owner_id_due_sort_id"),
                Arguments.of("todos of a list, NEWEST", "idx_todos_todo_list_id_owner_id_created_at_id"),
                Arguments.of("todos by status, DUE_DATE", "idx_todos_owner_id_status_due_sort_id"),
                Arguments.of("todos by status, NEWEST", "idx_todos_owner_id_status_created_at_id"),
                Arguments.of("todos by priority, DUE_DATE", "idx_todos_owner_id_priority_due_sort_id"),
                Arguments.of("todos by priority, NEWEST", "idx_todos_owner_id_priority_created_at_id"),
                Arguments.of("todos due on or before, DUE_DATE only (a range of the order's index)", "idx_todos_owner_id_due_sort_id"),
                Arguments.of("todos with a tag (the pair per todo)", "uq_todo_tags_todo_id_tag_id"),
                Arguments.of("todos by ids (and the plans' todo lookup)", "pk_todos"),
                Arguments.of("the tags of a todo (the list's second step, the detail, the todo's delete)", "uq_todo_tags_todo_id_tag_id"),
                Arguments.of("a tag's delete (its pairs, the cascade)", "idx_todo_tags_tag_id_owner_id_todo_id"),
                Arguments.of("the todo dropdown: one list's todos by title (both directions)", "idx_todos_todo_list_id_owner_id_title_id"),
                Arguments.of("todo lists, NAME (and the dropdown, both directions)", "idx_todo_lists_owner_id_name_id"),
                Arguments.of("todo lists, NEWEST", "idx_todo_lists_owner_id_created_at_id"),
                Arguments.of("todo lists by ids", "pk_todo_lists"),
                Arguments.of("tags by ids (a todo's tags on create and update)", "pk_tags"));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("offeredFiltersAndOrders")
    @DisplayName("Every offered filter and order of the todos and their lists has its index")
    void everyOfferedFilterAndOrderHasItsIndex(String served, String index) {
        assertThat(QueryPlan.indexExists(jdbc, index)).as(served).isTrue();
    }

    static Stream<Arguments> firstPages() {
        return Stream.of(
                Arguments.of("todos", "idx_todos_owner_id_due_sort_id",
                        TODO_SUMMARY + " where owner_id = :owner" + AND_TENANT + " order by due_sort, id limit 21"),
                Arguments.of("todo lists", "idx_todo_lists_owner_id_name_id",
                        TODO_LIST_ROW + " where owner_id = :owner" + AND_TENANT + " order by name, id limit 20"),
                Arguments.of("todo list dropdown", "idx_todo_lists_owner_id_name_id",
                        DROPDOWN_ITEM + " where owner_id = :owner" + AND_TENANT + " order by name, id limit 21"),
                Arguments.of("todo dropdown", "idx_todos_todo_list_id_owner_id_title_id",
                        TODO_DROPDOWN_ITEM + " where todo_list_id = :list and owner_id = :owner" + AND_TENANT
                                + " order by title, id limit 21"));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("firstPages")
    @DisplayName("Each list's first page is planned on its order's index, never a sequential scan")
    void theFirstPageIsPlannedOnItsIndex(String list, String index, String statement) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("owner", UUID.randomUUID())
                .addValue("list", UUID.randomUUID());
        // tenancy-row:begin
        // parameters.addValue("tenantId", testTenant().id());
        // tenancy-row:end

        String plan = QueryPlan.explain(jdbc, transactionManager, statement, parameters);

        assertThat(plan).as(list).doesNotContain("Seq Scan");
        assertThat(QueryPlan.indexesScanned(plan)).as(plan).contains(index);
    }

    static Stream<Arguments> laterBatches() {
        return Stream.of(
                Arguments.of("todos, DUE_DATE", "idx_todos_owner_id_due_sort_id", "ROW(due_sort, id) > ",
                        TODO_SUMMARY + " where owner_id = :owner" + AND_TENANT
                                + " and (due_sort, id) > (:day, :id) order by due_sort, id limit 21"),
                Arguments.of("todo list dropdown", "idx_todo_lists_owner_id_name_id", "ROW(name, id) > ",
                        DROPDOWN_ITEM + " where owner_id = :owner" + AND_TENANT
                                + " and (name, id) > (:text, :id) order by name, id limit 21"),
                Arguments.of("todo list dropdown, DESC", "idx_todo_lists_owner_id_name_id", "ROW(name, id) < ",
                        DROPDOWN_ITEM + " where owner_id = :owner" + AND_TENANT
                                + " and (name, id) < (:text, :id) order by name desc, id desc limit 21"),
                Arguments.of("todo dropdown", "idx_todos_todo_list_id_owner_id_title_id", "ROW(title, id) > ",
                        TODO_DROPDOWN_ITEM + " where todo_list_id = :list and owner_id = :owner" + AND_TENANT
                                + " and (title, id) > (:text, :id) order by title, id limit 21"));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("laterBatches")
    @DisplayName("Each cursor list's batch after a cursor starts at the cursor on its order's index: the seek is an index condition")
    void aLaterBatchSeeksOnItsOrdersIndex(String list, String index, String seek, String statement) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("owner", UUID.randomUUID())
                .addValue("list", UUID.randomUUID())
                .addValue("day", LocalDate.of(2026, 11, 1))
                .addValue("text", "Groceries")
                .addValue("id", UUID.randomUUID());
        // tenancy-row:begin
        // parameters.addValue("tenantId", testTenant().id());
        // tenancy-row:end

        String plan = QueryPlan.explain(jdbc, transactionManager, statement, parameters);

        assertThat(plan).as(list).doesNotContain("Seq Scan");
        assertThat(QueryPlan.indexesScanned(plan)).as(plan).contains(index);
        assertThat(plan.lines().filter(line -> line.contains("Index Cond")).toList()).as(plan)
                .anySatisfy(condition -> assertThat(condition).contains(seek));
    }
}
