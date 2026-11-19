package com.example.app.user;

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
 * The members list's indexes (crud-gen tests.md row 28): every offered filter and order has its index, and the
 * first page, with the statement of {@code TenantMembershipRepositoryImpl} (its select list and join), is
 * planned on its order's index.
 */
class TenantMemberIndexIntegrationTest extends IntegrationTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    // roles:begin
    private static final String MEMBER_ROWS = """
            select u.id, u.email, u.full_name, m.created_at, m.updated_at, m.created_by, m.updated_by
              from tenant_memberships m
              join users u on u.id = m.user_id""";
    // roles:else
    // private static final String MEMBER_ROWS = """
    //         select u.id, u.email, u.full_name, m.role, m.version, m.created_at, m.updated_at, m.created_by, m.updated_by
    //           from tenant_memberships m
    //           join users u on u.id = m.user_id""";
    // roles:end

    /** Each offered filter and order with the index that serves it. */
    static Stream<Arguments> offeredFiltersAndOrders() {
        return Stream.of(
                Arguments.of("members, NEWEST and OLDEST (one index, read either way)", "idx_tenant_memberships_tenant_id_created_at_id"),
                // roles:begin
                // roles:else
                // Arguments.of("members by role, NEWEST and OLDEST", "idx_tenant_memberships_tenant_id_role_created_at_id"),
                // roles:end
                Arguments.of("members by account ids", "uq_tenant_memberships_user_id_tenant_id"));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("offeredFiltersAndOrders")
    @DisplayName("Every offered filter and order of the members list has its index")
    void everyOfferedFilterAndOrderHasItsIndex(String served, String index) {
        assertThat(QueryPlan.indexExists(jdbc, index)).as(served).isTrue();
    }

    @Test
    @DisplayName("The first page (NEWEST) is planned on its index, never a sequential scan")
    void theFirstPageIsPlannedOnItsIndex() {
        String plan = QueryPlan.explain(jdbc, transactionManager,
                MEMBER_ROWS + " where m.tenant_id = :tenant order by m.created_at desc, m.id desc limit 20",
                new MapSqlParameterSource("tenant", UUID.randomUUID()));

        assertThat(plan).doesNotContain("Seq Scan");
        assertThat(QueryPlan.indexesScanned(plan)).as(plan).contains("idx_tenant_memberships_tenant_id_created_at_id");
    }
}
