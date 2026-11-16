package com.example.app.tenant.row;

import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Row mode, with the example: every table whose rows an owner scopes has the owner's and the tenant's
 * dependency statistics (0023, house-review TEN-33), so the planner knows an owner lives in one tenant.
 */
class OwnerTenantStatisticsIntegrationTest extends IntegrationTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"todo_lists", "todos", "tags", "time_entries", "todo_tags", "plans"})
    @DisplayName("Every owner-scoped table has its owner-tenant dependency statistics")
    void everyOwnerScopedTableHasItsStatistics(String table) {
        String statistics = "st_" + table + "_owner_id_tenant_id";

        assertThat(jdbc.queryForObject("""
                select count(*) from pg_statistic_ext
                 where stxname = ? and stxrelid = to_regclass(?) and 'f' = any (stxkind)""", Integer.class, statistics, table))
                .as(statistics).isEqualTo(1);
    }
}
