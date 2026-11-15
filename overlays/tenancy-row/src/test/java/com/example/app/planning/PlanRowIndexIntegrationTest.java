package com.example.app.planning;

import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Row mode: the owner's plan list reads its order's index with the table filled as production fills it
 * (crud-gen tests.md, index tests in row mode). The tested owner's 40 plans sit beside a bigger sibling
 * owner's 200 in the tenant, among four other tenants' 20,000, interleaved in id order (random ids). An
 * owner lives in one tenant, but without the dependency statistics of changeset 0023 the planner
 * multiplies the owner's share of the table by the tenant's, estimates one row, and reads the exclusion
 * constraint's GiST index (the same leading columns) plus a sort instead. Whole-table scans are switched
 * off: on a test database's few rows they would win on cost and hide a missing index. The seeded rows
 * are deleted afterwards, so the other index tests keep the table they seed for.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlanRowIndexIntegrationTest extends IntegrationTest {

    private static final int OTHER_TENANTS = 4;
    private static final int OWNERS_PER_OTHER_TENANT = 10;
    private static final int PLANS_PER_OTHER_OWNER = 500;

    private static final String PLAN_ROW = """
            select id, todo_id, owner_id, starts_on, ends_on, created_at, updated_at, created_by, updated_by, version
              from plans""";

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<TestTenant> otherTenants = new ArrayList<>();
    private UUID tenantId;
    private UUID ownerId;
    private UUID siblingId;

    @BeforeAll
    void seedTheOwnerAmongBiggerTenants() throws Exception {
        tenantId = testTenant().id();
        ownerId = newUser().id();
        siblingId = newUser().id();
        seedOwner(ownerId, 40);
        seedOwner(siblingId, 200);
        for (int other = 0; other < OTHER_TENANTS; other++) {
            TestTenant tenant = newTenant();
            otherTenants.add(tenant);
            seedOtherTenant(tenant);
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("set local default_statistics_target = 1000");
            jdbc.execute("analyze plans");
        });
    }

    @AfterAll
    void deleteTheSeededRows() {
        for (UUID owner : List.of(ownerId, siblingId)) {
            sql("delete from plans where owner_id = ?", owner);
            sql("delete from todos where owner_id = ?", owner);
            sql("delete from todo_lists where owner_id = ?", owner);
        }
        for (TestTenant tenant : otherTenants) {
            sqlIn(tenant, "delete from plans where tenant_id = ?", tenant.id());
            sqlIn(tenant, "delete from todos where tenant_id = ?", tenant.id());
            sqlIn(tenant, "delete from todo_lists where tenant_id = ?", tenant.id());
        }
    }

    /** One list of {@code plans} todos for a member of the test tenant, each todo planned for a day of its own. */
    private void seedOwner(UUID owner, int plans) {
        sql("""
                insert into todo_lists (id, tenant_id, owner_id, name, created_at, updated_at, version)
                values (gen_random_uuid(), ?, ?, 'Seeded', now(), now(), 0)
                """, tenantId, owner);
        sql("""
                insert into todos (id, tenant_id, todo_list_id, owner_id, title, status, priority, created_at, updated_at, version)
                select gen_random_uuid(), list.tenant_id, list.id, list.owner_id, 'Seeded ' || n, 'OPEN', 'LOW', now(), now(), 0
                  from todo_lists list cross join generate_series(1, ?) n
                 where list.owner_id = ?
                """, plans, owner);
        sql("""
                insert into plans (id, tenant_id, todo_id, owner_id, starts_on, ends_on, created_at, updated_at, version)
                select gen_random_uuid(), todo.tenant_id, todo.id, todo.owner_id, date '2020-01-01' + todo.rank,
                       date '2020-01-01' + todo.rank, now(), now(), 0
                  from (select id, tenant_id, owner_id, (row_number() over (order by id))::integer as rank
                          from todos where owner_id = ?) todo
                """, owner);
    }

    /** Another tenant's members (no accounts: the tenant's own rows only), each with a list of todos and their plans. */
    private void seedOtherTenant(TestTenant tenant) {
        sqlIn(tenant, """
                insert into members (id, tenant_id, user_id, created_at, updated_at, version)
                select gen_random_uuid(), ?, gen_random_uuid(), now(), now(), 0 from generate_series(1, ?)
                """, tenant.id(), OWNERS_PER_OTHER_TENANT);
        sqlIn(tenant, """
                insert into todo_lists (id, tenant_id, owner_id, name, created_at, updated_at, version)
                select gen_random_uuid(), tenant_id, user_id, 'Seeded', now(), now(), 0
                  from members where tenant_id = ? and user_id not in (select owner_id from todo_lists where tenant_id = ?)
                """, tenant.id(), tenant.id());
        sqlIn(tenant, """
                insert into todos (id, tenant_id, todo_list_id, owner_id, title, status, priority, created_at, updated_at, version)
                select gen_random_uuid(), list.tenant_id, list.id, list.owner_id, 'Seeded ' || n, 'OPEN', 'LOW', now(), now(), 0
                  from todo_lists list cross join generate_series(1, ?) n
                 where list.tenant_id = ? and list.name = 'Seeded'
                """, PLANS_PER_OTHER_OWNER, tenant.id());
        sqlIn(tenant, """
                insert into plans (id, tenant_id, todo_id, owner_id, starts_on, ends_on, created_at, updated_at, version)
                select gen_random_uuid(), todo.tenant_id, todo.id, todo.owner_id, date '2020-01-01' + todo.rank,
                       date '2020-01-01' + todo.rank, now(), now(), 0
                  from (select id, tenant_id, owner_id, (row_number() over (partition by owner_id order by id))::integer as rank
                          from todos where tenant_id = ? and title like 'Seeded %') todo
                """, tenant.id());
    }

    static Stream<Arguments> ownerOrders() {
        return Stream.of(
                Arguments.of("the owner's plans, STARTS_ON", "order by starts_on, id", "idx_plans_owner_id_starts_on_id"),
                Arguments.of("the owner's plans, NEWEST", "order by id desc", "idx_plans_owner_id_id"));
    }

    @ParameterizedTest(name = "{0}: {2}")
    @MethodSource("ownerOrders")
    @DisplayName("Among bigger tenants, the owner's plans read the order's index in order, not the exclusion constraint's")
    void theOwnersPlansReadTheOrdersIndex(String name, String orderBy, String index) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("owner", ownerId)
                .addValue("tenant", tenantId);

        List<String> planLines = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.execute("set local enable_seqscan = off");
            jdbc.execute("set local enable_bitmapscan = off");
            return new NamedParameterJdbcTemplate(jdbc).queryForList("explain " + PLAN_ROW
                    + " where owner_id = :owner and tenant_id = :tenant " + orderBy + " limit 21", parameters, String.class);
        });
        String plan = String.join("\n", planLines);

        assertThat(plan).as(plan).contains("using " + index + " on").doesNotContain("Sort");
    }
}
