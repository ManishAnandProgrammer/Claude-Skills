package com.example.app.planning;

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
 * The plans' indexes (crud-gen tests.md row 28): every offered filter and order has its index, and the first
 * page is planned on its order's index.
 */
class PlanIndexIntegrationTest extends IntegrationTest {

    private static final String PLAN_ROW = """
            select id, todo_id, owner_id, starts_on, ends_on, created_at, updated_at, created_by, updated_by, version
              from plans""";

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** Each offered filter and order, the todo's foreign key check and the overlap check, with their index. */
    static Stream<Arguments> offeredFiltersAndOrders() {
        return Stream.of(
                Arguments.of("plans, STARTS_ON (and the planned days, offered under it only: a range of it)",
                        "idx_plans_owner_id_starts_on_id"),
                Arguments.of("plans, NEWEST", "idx_plans_owner_id_created_at_id"),
                Arguments.of("plans of a todo, STARTS_ON (and the todo's delete: its plans' key check)",
                        "idx_plans_todo_id_owner_id_starts_on_id"),
                Arguments.of("plans of a todo, NEWEST", "idx_plans_todo_id_owner_id_created_at_id"),
                Arguments.of("plans by ids", "pk_plans"),
                Arguments.of("the overlap check (the exclusion constraint)", "ex_plans_owner_id_dates"));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("offeredFiltersAndOrders")
    @DisplayName("Every offered filter and order of the plans has its index")
    void everyOfferedFilterAndOrderHasItsIndex(String served, String index) {
        assertThat(QueryPlan.indexExists(jdbc, index)).as(served).isTrue();
    }

    @Test
    @DisplayName("The first page (STARTS_ON) is planned on its index, never a sequential scan")
    void theFirstPageIsPlannedOnItsIndex() {
        String plan = QueryPlan.explain(jdbc, transactionManager,
                PLAN_ROW + " where owner_id = :owner order by starts_on, id limit 21", new MapSqlParameterSource("owner", UUID.randomUUID()));

        assertThat(plan).doesNotContain("Seq Scan");
        assertThat(QueryPlan.indexesScanned(plan)).as(plan).contains("idx_plans_owner_id_starts_on_id");
    }
}
