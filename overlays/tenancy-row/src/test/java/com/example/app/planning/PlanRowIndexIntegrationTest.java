package com.example.app.planning;

import com.example.app.support.IntegrationTest;
import com.example.app.support.ReadCost;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Row mode: a page of the owner's plans reads at most two pages with the table filled as production fills
 * it (crud-gen tests.md, index tests in row mode, row 28). The tested owner's 100 plans sit beside a bigger
 * sibling owner's 200 in the tenant, among four other tenants' 20,000, seeded tenant after tenant with
 * UUIDv7 ids as the application writes them, so the tested tenant's plans are the oldest (the tenant whose
 * rows are old is the one an id order would walk every newer tenant's rows for). An owner lives in one tenant, but without the dependency statistics of changeset 0023 the planner
 * multiplies the owner's share of the table by the tenant's, estimates a row or two, and reads the
 * exclusion constraint's GiST index (the same leading columns) plus a sort of all the owner's plans
 * instead. The seeded rows are deleted afterwards, so the other index tests keep the table they seed for.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlanRowIndexIntegrationTest extends IntegrationTest {

    /** The tested owner's plans: more than two pages, so a sort of all of them reads past the bound. */
    private static final int OWNER_PLANS = 100;
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
        seedOwner(ownerId, OWNER_PLANS);
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
                insert into todos (id, %stenant_id, todo_list_id, owner_id, title, status, priority, created_at, updated_at, version)
                select gen_random_uuid(), %slist.tenant_id, list.id, list.owner_id, 'Seeded ' || n, 'OPEN', 'LOW', now(), now(), 0
                  from todo_lists list cross join generate_series(1, ?) n
                 where list.owner_id = ?
                """.formatted(TODO_NUMBER_COLUMN, TODO_NUMBER), plans, owner);
        sql("""
                insert into plans (id, tenant_id, todo_id, owner_id, starts_on, ends_on, created_at, updated_at, version)
                select uuidv7(), todo.tenant_id, todo.id, todo.owner_id, date '2020-01-01' + todo.rank,
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
                insert into todos (id, %stenant_id, todo_list_id, owner_id, title, status, priority, created_at, updated_at, version)
                select gen_random_uuid(), %slist.tenant_id, list.id, list.owner_id, 'Seeded ' || n, 'OPEN', 'LOW', now(), now(), 0
                  from todo_lists list cross join generate_series(1, ?) n
                 where list.tenant_id = ? and list.name = 'Seeded'
                """.formatted(TODO_NUMBER_COLUMN, TODO_NUMBER), PLANS_PER_OTHER_OWNER, tenant.id());
        sqlIn(tenant, """
                insert into plans (id, tenant_id, todo_id, owner_id, starts_on, ends_on, created_at, updated_at, version)
                select uuidv7(), todo.tenant_id, todo.id, todo.owner_id, date '2020-01-01' + todo.rank,
                       date '2020-01-01' + todo.rank, now(), now(), 0
                  from (select id, tenant_id, owner_id, (row_number() over (partition by owner_id order by id))::integer as rank
                          from todos where tenant_id = ? and title like 'Seeded %') todo
                """, tenant.id());
    }

    static Stream<Arguments> ownerOrders() {
        return Stream.of(
                Arguments.of("the owner's plans, STARTS_ON", "idx_plans_owner_id_starts_on_id", "order by starts_on, id"),
                Arguments.of("the owner's plans, NEWEST", "idx_plans_owner_id_created_at_id", "order by created_at desc, id desc"));
    }

    /**
     * A page reads at most two pages: without the dependency statistics the planner estimates the owner's
     * plans at one row, reads the GiST index of the exclusion constraint and sorts all of them (OWNER_PLANS).
     */
    @ParameterizedTest(name = "{0} ({1})")
    @MethodSource("ownerOrders")
    @DisplayName("Among bigger tenants, a page of the owner's plans has its index and reads at most two pages")
    void theOwnersPlansReadAtMostTwoPages(String name, String index, String orderBy) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("owner", ownerId)
                .addValue("tenant", tenantId);

        ReadCost cost = ReadCost.of(jdbc, transactionManager,
                PLAN_ROW + " where owner_id = :owner and tenant_id = :tenant " + orderBy + " limit 21", parameters);

        assertThat(ReadCost.indexExists(jdbc, index)).as(index).isTrue();
        assertThat(cost.rowsRead()).as(cost.plan()).isLessThanOrEqualTo(42);
    }
}
