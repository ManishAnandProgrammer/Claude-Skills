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

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tag list's and dropdown's indexes (crud-gen tests.md row 28): every offered filter and order has its
 * index, and each list's first page is planned on its order's index.
 */
class TagIndexIntegrationTest extends IntegrationTest {

    private static final String TAG_ROW = """
            select id, name, color, active, owner_id, created_at, updated_at, created_by, updated_by, version
              from tags""";
    private static final String DROPDOWN_ITEM = "select id, name from tags";
    // tenancy-row:begin
    // // The repository's query names the tenant (Hibernate's @TenantId adds it), so the statement written as
    // // the repository issues it does too.
    // private static final String AND_TENANT = " and tenant_id = :tenantId";
    // tenancy-row:else
    private static final String AND_TENANT = "";
    // tenancy-row:end

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** Each offered filter and order with the index that serves it. */
    static Stream<Arguments> offeredFiltersAndOrders() {
        return Stream.of(
                Arguments.of("tags, NAME (and the dropdown's ALL, both directions)", "idx_tags_owner_id_name_id"),
                Arguments.of("tags, NEWEST", "idx_tags_owner_id_created_at_id"),
                Arguments.of("tags by status, NAME (and the dropdown's ACTIVE and INACTIVE)", "idx_tags_owner_id_active_name_id"),
                Arguments.of("tags by status, NEWEST", "idx_tags_owner_id_active_created_at_id"),
                Arguments.of("tags by ids", "pk_tags"));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("offeredFiltersAndOrders")
    @DisplayName("Every offered filter and order of the tags has its index")
    void everyOfferedFilterAndOrderHasItsIndex(String served, String index) {
        assertThat(QueryPlan.indexExists(jdbc, index)).as(served).isTrue();
    }

    /**
     * The list's and the dropdown's first page in their default order. The dropdown defaults to ACTIVE: walking
     * the name index and skipping the inactive tags is an equally good plan as the status index, so either passes.
     */
    static Stream<Arguments> firstPages() {
        return Stream.of(
                Arguments.of("tags", List.of("idx_tags_owner_id_name_id"), TAG_ROW + " where owner_id = :owner" + AND_TENANT + " order by name, id limit 20"),
                Arguments.of("tag dropdown", List.of("idx_tags_owner_id_active_name_id", "idx_tags_owner_id_name_id"),
                        DROPDOWN_ITEM + " where owner_id = :owner and active = :active" + AND_TENANT + " order by name, id limit 21"));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("firstPages")
    @DisplayName("Each list's first page is planned on one of its indexes, never a sequential scan")
    void theFirstPageIsPlannedOnItsIndex(String list, List<String> indexes, String statement) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("owner", UUID.randomUUID()).addValue("active", true);
        // tenancy-row:begin
        // parameters.addValue("tenantId", testTenant().id());
        // tenancy-row:end

        String plan = QueryPlan.explain(jdbc, transactionManager, statement, parameters);

        assertThat(plan).as(list).doesNotContain("Seq Scan");
        assertThat(QueryPlan.indexesScanned(plan)).as(plan).containsAnyElementsOf(indexes);
    }
}
