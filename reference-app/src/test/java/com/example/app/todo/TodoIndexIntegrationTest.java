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

import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The todo, todo list and todo-tag indexes (crud-gen tests.md row 28): every offered filter and order has its
 * index, and each list's first page is planned on its order's index.
 */
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

    /** Each offered filter and order, and each foreign key's check, with the index that serves it. */
    static Stream<Arguments> offeredFiltersAndOrders() {
        return Stream.of(
                Arguments.of("todos, DUE_DATE", "idx_todos_owner_id_due_sort_id"),
                Arguments.of("todos, NEWEST", "idx_todos_owner_id_created_at_id"),
                Arguments.of("todos of a list, DUE_DATE (and the list's delete: its todos' key check)",
                        "idx_todos_todo_list_id_owner_id_due_sort_id"),
                Arguments.of("todos of a list, NEWEST", "idx_todos_todo_list_id_owner_id_created_at_id"),
                Arguments.of("todos by status, DUE_DATE", "idx_todos_owner_id_status_due_sort_id"),
                Arguments.of("todos by status, NEWEST", "idx_todos_owner_id_status_created_at_id"),
                // 0003: no priority is rare, so the filter is applied while walking the order's index.
                Arguments.of("todos by priority, DUE_DATE (the order's index)", "idx_todos_owner_id_due_sort_id"),
                Arguments.of("todos by priority, NEWEST (the order's index)", "idx_todos_owner_id_created_at_id"),
                Arguments.of("todos due on or before, DUE_DATE only (a range of the order's index)", "idx_todos_owner_id_due_sort_id"),
                Arguments.of("todos with a tag (the pair per todo)", "uq_todo_tags_todo_id_tag_id"),
                Arguments.of("todos by ids (and the plans' todo lookup)", "pk_todos"),
                Arguments.of("the tags of a todo (the list's second step, the detail, the todo's delete)", "uq_todo_tags_todo_id_tag_id"),
                Arguments.of("a tag's delete (its pairs, the cascade)", "idx_todo_tags_tag_id_owner_id_todo_id"),
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

    /** Each list's first page in its default order, as its repository issues it, with its order's index. */
    static Stream<Arguments> firstPages() {
        return Stream.of(
                Arguments.of("todos", "idx_todos_owner_id_due_sort_id",
                        TODO_SUMMARY + " where owner_id = :owner order by due_sort, id limit 21"),
                Arguments.of("todo lists", "idx_todo_lists_owner_id_name_id",
                        TODO_LIST_ROW + " where owner_id = :owner order by name, id limit 20"),
                Arguments.of("todo list dropdown", "idx_todo_lists_owner_id_name_id",
                        DROPDOWN_ITEM + " where owner_id = :owner order by name, id limit 21"));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("firstPages")
    @DisplayName("Each list's first page is planned on its order's index, never a sequential scan")
    void theFirstPageIsPlannedOnItsIndex(String list, String index, String statement) {
        String plan = QueryPlan.explain(jdbc, transactionManager, statement, new MapSqlParameterSource("owner", UUID.randomUUID()));

        assertThat(plan).as(list).doesNotContain("Seq Scan");
        assertThat(QueryPlan.indexesScanned(plan)).as(plan).contains(index);
    }
}
