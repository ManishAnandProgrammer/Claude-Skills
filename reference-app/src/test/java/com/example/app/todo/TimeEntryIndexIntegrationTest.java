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
 * A todo's time entries (crud-gen tests.md row 28): every offered filter and order has its index, and the first
 * page is planned on its order's index. Each range filter is offered under its own column's order only.
 */
class TimeEntryIndexIntegrationTest extends IntegrationTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static final String ENTRY_ROW = """
            select id, todo_id, owner_id, minutes, spent_on, note, billable, created_at, updated_at, created_by, updated_by, version
              from time_entries""";
    // tenancy-row:begin
    // // The repository's query names the tenant (Hibernate's @TenantId adds it), so the statement written as
    // // the repository issues it does too.
    // private static final String AND_TENANT = " and tenant_id = :tenantId";
    // tenancy-row:else
    private static final String AND_TENANT = "";
    // tenancy-row:end

    /** Each offered filter and order with the index that serves it. */
    static Stream<Arguments> offeredFiltersAndOrders() {
        return Stream.of(
                Arguments.of("SPENT_ON (and spentOnFrom/To, a range of it)", "idx_time_entries_todo_id_owner_id_spent_on_id"),
                Arguments.of("MINUTES (and minMinutes/maxMinutes, a range of it)", "idx_time_entries_todo_id_owner_id_minutes_id"),
                Arguments.of("billable, SPENT_ON", "idx_time_entries_todo_id_owner_id_billable_spent_on_id"),
                Arguments.of("billable, MINUTES", "idx_time_entries_todo_id_owner_id_billable_minutes_id"),
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
        MapSqlParameterSource parameters = new MapSqlParameterSource("todo", UUID.randomUUID()).addValue("owner", UUID.randomUUID());
        // tenancy-row:begin
        // parameters.addValue("tenantId", testTenant().id());
        // tenancy-row:end

        String plan = QueryPlan.explain(jdbc, transactionManager,
                ENTRY_ROW + " where todo_id = :todo and owner_id = :owner" + AND_TENANT + " order by spent_on desc, id desc limit 21",
                parameters);

        assertThat(plan).doesNotContain("Seq Scan");
        assertThat(QueryPlan.indexesScanned(plan)).as(plan).contains("idx_time_entries_todo_id_owner_id_spent_on_id");
    }
}
