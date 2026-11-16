package com.example.app.todo;

import com.example.app.support.IntegrationTest;
import com.example.app.support.QueryPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * A todo's time entries (crud-gen tests.md row 28): each order has its index, and the first page is planned on
 * it. The list is always one todo's entries (the path scopes it), so the other filters (the other order's
 * range, billable) are applied while walking the todo's entries (0005: one todo's entries are few).
 */
class TimeEntryIndexIntegrationTest extends IntegrationTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static final String ENTRY_ROW = """
            select id, todo_id, owner_id, minutes, spent_on, note, billable, created_at, updated_at, created_by, updated_by, version
              from time_entries""";

    /** Each offered filter and order with the index that serves it. */
    static Stream<Arguments> offeredFiltersAndOrders() {
        return Stream.of(
                Arguments.of("SPENT_ON (and spentOnFrom/To, a range of it)", "idx_time_entries_todo_id_owner_id_spent_on_id"),
                Arguments.of("MINUTES (and minMinutes/maxMinutes, a range of it)", "idx_time_entries_todo_id_owner_id_minutes_id"),
                Arguments.of("by ids", "pk_time_entries"));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("offeredFiltersAndOrders")
    @DisplayName("Every offered filter and order of a todo's time entries has its index")
    void everyOfferedFilterAndOrderHasItsIndex(String served, String index) {
        assertThat(QueryPlan.indexExists(jdbc, index)).as(served).isTrue();
    }

    @Test
    @DisplayName("The first page (SPENT_ON) is planned on its index, never a sequential scan")
    void theFirstPageIsPlannedOnItsIndex() {
        String plan = QueryPlan.explain(jdbc, transactionManager,
                ENTRY_ROW + " where todo_id = :todo and owner_id = :owner order by spent_on desc, id desc limit 21",
                new MapSqlParameterSource("todo", UUID.randomUUID()).addValue("owner", UUID.randomUUID()));

        assertThat(plan).doesNotContain("Seq Scan");
        assertThat(QueryPlan.indexesScanned(plan)).as(plan).contains("idx_time_entries_todo_id_owner_id_spent_on_id");
    }
}
