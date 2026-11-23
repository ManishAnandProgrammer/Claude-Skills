package com.example.app.tenant;

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

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class TenantIndexIntegrationTest extends IntegrationTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    // The select list of the repository's projection: "select id" alone could be answered from an index only, a
    // plan the real query never gets.
    private static final String TENANT_ROW = """
            select id, name, status, created_at, updated_at, created_by, updated_by, version
              from tenants""";

    static Stream<Arguments> offeredFiltersAndOrders() {
        return Stream.of(
                Arguments.of("tenants, NEWEST", "idx_tenants_created_at_id"),
                Arguments.of("tenants, NAME", "idx_tenants_name_id"),
                Arguments.of("tenants by status, NEWEST", "idx_tenants_status_created_at_id"),
                Arguments.of("tenants by status, NAME", "idx_tenants_status_name_id"));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("offeredFiltersAndOrders")
    @DisplayName("Every offered filter and order of the tenant list has its index")
    void everyOfferedFilterAndOrderHasItsIndex(String served, String index) {
        assertThat(QueryPlan.indexExists(jdbc, index)).as(served).isTrue();
    }

    @Test
    @DisplayName("The first page (NEWEST) is planned on its index, never a sequential scan")
    void theFirstPageIsPlannedOnItsIndex() {
        String plan = QueryPlan.explain(jdbc, transactionManager,
                TENANT_ROW + " order by created_at desc, id desc limit 20", new MapSqlParameterSource());

        assertThat(plan).doesNotContain("Seq Scan");
        assertThat(QueryPlan.indexesScanned(plan)).as(plan).contains("idx_tenants_created_at_id");
    }
}
