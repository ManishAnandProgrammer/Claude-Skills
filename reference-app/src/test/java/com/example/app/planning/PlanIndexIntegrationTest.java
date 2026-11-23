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

import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PlanIndexIntegrationTest extends IntegrationTest {

    // The select list of the repository's projection: "select id" alone could be answered from an index only, a
    // plan the real query never gets.
    private static final String PLAN_ROW = """
            select id, todo_id, owner_id, starts_on, ends_on, status, done_on, created_at, updated_at, created_by, updated_by, version
              from plans""";
    // tenancy-row:begin
    // private static final String AND_TENANT = " and tenant_id = :tenantId";
    // tenancy-row:else
    private static final String AND_TENANT = "";
    // tenancy-row:end

    @Autowired
    private PlatformTransactionManager transactionManager;

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
        MapSqlParameterSource parameters = new MapSqlParameterSource("owner", UUID.randomUUID());
        // tenancy-row:begin
        // parameters.addValue("tenantId", testTenant().id());
        // tenancy-row:end

        String plan = QueryPlan.explain(jdbc, transactionManager,
                PLAN_ROW + " where owner_id = :owner" + AND_TENANT + " order by starts_on, id limit 21", parameters);

        assertThat(plan).doesNotContain("Seq Scan");
        assertThat(QueryPlan.indexesScanned(plan)).as(plan).contains("idx_plans_owner_id_starts_on_id");
    }

    @Test
    @DisplayName("The availability read (PlanRepositoryImpl.findLiveDays) is planned on the starts_on index, never a sequential scan")
    void theAvailabilityIsPlannedOnTheStartsOnIndex() {
        MapSqlParameterSource parameters = new MapSqlParameterSource("owner", UUID.randomUUID())
                .addValue("lowest", LocalDate.parse("2026-02-07"))
                .addValue("from", LocalDate.parse("2026-03-09"))
                .addValue("to", LocalDate.parse("2026-03-16"));
        // tenancy-row:begin
        // parameters.addValue("tenantId", testTenant().id());
        // tenancy-row:end

        String plan = QueryPlan.explain(jdbc, transactionManager, """
                select starts_on, ends_on from plans
                 where owner_id = :owner and status <> 'CANCELLED'
                   and starts_on >= :lowest and starts_on <= :to and ends_on >= :from"""
                + AND_TENANT + " order by starts_on", parameters);

        assertThat(plan).doesNotContain("Seq Scan");
        assertThat(QueryPlan.indexesScanned(plan)).as(plan).contains("idx_plans_owner_id_starts_on_id");
    }
}
