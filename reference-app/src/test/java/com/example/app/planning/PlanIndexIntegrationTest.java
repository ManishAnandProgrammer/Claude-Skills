package com.example.app.planning;

import com.example.app.support.IntegrationTest;
import com.example.app.support.ReadCost;
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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every cell of the plan reads (each order, plain and under the todo filter, a later batch, the planned-days
 * overlap in both orders, the ids lookup, the todo's foreign key check and the overlap check) has its index
 * and reads at most its bound (crud-gen tests.md row 28), with the statements PlanRepositoryImpl issues.
 * The owner's 2,000 plans, one a day over five todos, sit beside a sibling owner's 20,000, written as the
 * application writes them (UUIDv7 ids and clock times, twenty rounds of the owner's then the sibling's, so the
 * heap follows the ids): a cell its index doesn't serve reads at least ten times its bound.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlanIndexIntegrationTest extends IntegrationTest {

    private static final String PLAN_ROW = """
            select id, todo_id, owner_id, starts_on, ends_on, created_at, updated_at, created_by, updated_by, version
              from plans""";
    private static final int PAGE = 21;
    private static final int TWO_PAGES = 2 * PAGE;
    private static final int OWNER_PLANS = 2_000;
    private static final int TODO_PLANS = 400;
    /** plan.max-days (limits.yml): the planned-days filter's lower bound reaches back this far. */
    private static final int MAX_DAYS = 31;
    private static final LocalDate PLANNED_FROM = LocalDate.of(2021, 6, 1);
    private static final LocalDate PLANNED_TO = PLANNED_FROM.plusDays(6);
    /** The planned-days range read from its index: the days a plan may reach back, and the week itself. */
    private static final int PLANNED_DAYS = MAX_DAYS + 7;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final MapSqlParameterSource parameters = new MapSqlParameterSource();

    @BeforeAll
    void seedAnOwnerBesideABiggerOne() throws Exception {
        UUID ownerId = newUser().id();
        UUID siblingId = newUser().id();
        seedTodos(ownerId, 5);
        seedTodos(siblingId, 50);
        // Twenty rounds of 20 days, the owner's plans then the sibling's: rows reach the heap in id order, as in production.
        for (int round = 0; round < 20; round++) {
            seedPlans(ownerId, 5, round * TODO_PLANS / 20, TODO_PLANS / 20);
            seedPlans(siblingId, 50, round * TODO_PLANS / 20, TODO_PLANS / 20);
        }
        UUID todoId = jdbc.queryForObject("select todo_id from plans where owner_id = ? limit 1", UUID.class, ownerId);
        Map<String, Object> middle = jdbc.queryForMap(
                "select starts_on, id from plans where owner_id = ? order by starts_on, id offset 1000 limit 1", ownerId);
        List<UUID> planIds = jdbc.queryForList("select id from plans where owner_id = ? limit 21", UUID.class, ownerId);
        UUID middleByNewest = jdbc.queryForObject(
                "select id from plans where owner_id = ? order by created_at desc, id desc offset 1000 limit 1", UUID.class, ownerId);
        parameters.addValue("owner", ownerId)
                .addValue("todo", todoId)
                .addValue("ids", planIds)
                .addValue("seekDay", middle.get("starts_on"))
                .addValue("seekId", middle.get("id"))
                .addValue("seekNewest", middleByNewest)
                // As an OffsetDateTime: a java.sql.Timestamp would be bound in the JVM's time zone.
                .addValue("seekCreated", jdbc.queryForObject("select created_at from plans where id = ?", OffsetDateTime.class, middleByNewest))
                .addValue("day", PLANNED_FROM)
                .addValue("from", PLANNED_FROM)
                .addValue("earliestStart", PLANNED_FROM.minusDays(MAX_DAYS - 1))
                .addValue("to", PLANNED_TO);
        // A finer sample: in a full run the tables also hold every other class's rows (other tenants' too).
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("set local default_statistics_target = 1000");
            jdbc.execute("analyze todos, plans");
        });
    }

    /** One list of the owner with {@code todos} todos, the plans' parents. */
    private void seedTodos(UUID owner, int todos) {
        // tenancy-row:begin
        // // Row mode: every insert names its tenant (tenant_id has no default).
        // sql("""
        //         insert into todo_lists (id, tenant_id, owner_id, name, created_at, updated_at, version)
        //         values (uuidv7(), ?, ?, 'Seeded', now(), now(), 0)
        //         """, testTenant().id(), owner);
        // sql("""
        //         insert into todos (id, %stenant_id, todo_list_id, owner_id, title, status, priority, created_at, updated_at, version)
        //         select uuidv7(), %slist.tenant_id, list.id, list.owner_id, 'Seeded ' || n, 'OPEN', 'LOW', now(), now(), 0
        //         from todo_lists list cross join generate_series(1, ?) n
        //         where list.owner_id = ?
        //         """.formatted(TODO_NUMBER_COLUMN, TODO_NUMBER), todos, owner);
        // tenancy-row:else
        sql("""
                insert into todo_lists (id, owner_id, name, created_at, updated_at, version)
                values (uuidv7(), ?, 'Seeded', now(), now(), 0)
                """, owner);
        sql("""
                insert into todos (id, %stodo_list_id, owner_id, title, status, priority, created_at, updated_at, version)
                select uuidv7(), %slist.id, list.owner_id, 'Seeded ' || n, 'OPEN', 'LOW', now(), now(), 0
                from todo_lists list cross join generate_series(1, ?) n
                where list.owner_id = ?
                """.formatted(TODO_NUMBER_COLUMN, TODO_NUMBER), todos, owner);
        // tenancy-row:end
    }

    /**
     * {@code days} more days of plans for each of the owner's {@code todos} todos, from day {@code firstDay},
     * one plan a day per todo (one owner's plans never share a day), created now: UUIDv7 ids and clock times.
     */
    private void seedPlans(UUID owner, int todos, int firstDay, int days) {
        // tenancy-row:begin
        // sql("""
        //         insert into plans (id, tenant_id, todo_id, owner_id, starts_on, ends_on, created_at, updated_at, version)
        //         select uuidv7(), todo.tenant_id, todo.id, todo.owner_id, date '2020-01-01' + (n * ? + todo.rank),
        //                date '2020-01-01' + (n * ? + todo.rank), clock_timestamp(), now(), 0
        //         from (select id, tenant_id, owner_id, (row_number() over (order by id) - 1)::integer as rank from todos where owner_id = ?) todo
        //         cross join generate_series(?, ?) n
        //         """, todos, todos, owner, firstDay, firstDay + days - 1);
        // tenancy-row:else
        sql("""
                insert into plans (id, todo_id, owner_id, starts_on, ends_on, created_at, updated_at, version)
                select uuidv7(), todo.id, todo.owner_id, date '2020-01-01' + (n * ? + todo.rank),
                       date '2020-01-01' + (n * ? + todo.rank), clock_timestamp(), now(), 0
                from (select id, owner_id, (row_number() over (order by id) - 1)::integer as rank from todos where owner_id = ?) todo
                cross join generate_series(?, ?) n
                """, todos, todos, owner, firstDay, firstDay + days - 1);
        // tenancy-row:end
    }

    private static Arguments cell(String name, String index, String statement, int bound) {
        return Arguments.of(name, index, statement, bound);
    }

    static Stream<Arguments> cells() {
        String plannedDays = PLAN_ROW + """
                 where owner_id = :owner and ends_on >= :from and starts_on >= :earliestStart and starts_on <= :to
                """;
        return Stream.of(
                cell("plans, STARTS_ON", "idx_plans_owner_id_starts_on_id",
                        PLAN_ROW + " where owner_id = :owner order by starts_on, id limit 21", TWO_PAGES),
                // The seek's row value starts the scan exactly at the cursor (Seek.after).
                cell("plans, STARTS_ON, a later batch", "idx_plans_owner_id_starts_on_id",
                        PLAN_ROW + " where owner_id = :owner and (starts_on, id) > (:seekDay, :seekId) order by starts_on, id limit 21",
                        TWO_PAGES),
                cell("plans, NEWEST", "idx_plans_owner_id_created_at_id",
                        PLAN_ROW + " where owner_id = :owner order by created_at desc, id desc limit 21", TWO_PAGES),
                cell("plans, NEWEST, a later batch", "idx_plans_owner_id_created_at_id",
                        PLAN_ROW + " where owner_id = :owner and (created_at, id) < (:seekCreated, :seekNewest)"
                                + " order by created_at desc, id desc limit 21", TWO_PAGES),
                cell("plans of a todo, STARTS_ON", "idx_plans_todo_id_owner_id_starts_on_id",
                        PLAN_ROW + " where owner_id = :owner and todo_id = :todo order by starts_on, id limit 21", TWO_PAGES),
                cell("plans of a todo, NEWEST", "idx_plans_todo_id_owner_id_created_at_id",
                        PLAN_ROW + " where owner_id = :owner and todo_id = :todo order by created_at desc, id desc limit 21", TWO_PAGES),
                // ends_on >= from has no index: the derived starts_on >= from - (max-days - 1) bounds the range
                // from below, so the filter reads the week and the days a plan reaches back, not the owner's history.
                cell("plans sharing a day with plannedFrom..plannedTo, STARTS_ON", "idx_plans_owner_id_starts_on_id",
                        plannedDays + " order by starts_on, id limit 21", PLANNED_DAYS),
                cell("plans sharing a day with plannedFrom..plannedTo, NEWEST", "idx_plans_owner_id_starts_on_id",
                        plannedDays + " order by created_at desc, id desc limit 21", PLANNED_DAYS),
                // The ids filter under every order: the asked rows through the primary key, then sorted.
                cell("plans by ids, STARTS_ON", "pk_plans",
                        PLAN_ROW + " where owner_id = :owner and id in (:ids) order by starts_on, id limit 21", TWO_PAGES),
                cell("plans by ids, NEWEST", "pk_plans",
                        PLAN_ROW + " where owner_id = :owner and id in (:ids) order by created_at desc, id desc limit 21", TWO_PAGES),
                cell("a todo's delete (its foreign key's check: the todo's own plans)", "idx_plans_todo_id_owner_id_starts_on_id",
                        "select 1 from plans where todo_id = :todo and owner_id = :owner", TODO_PLANS),
                // tenancy-row:begin
                // // Row mode: && isn't leakproof, so it can't run before the tenant policy and the constraint's GiST
                // // index can't take it (house-review TEN-36): the owner's plans in the tenant are read through a btree
                // // index and filtered. The constraint itself still decides (key checks don't pass the policy).
                // cell("an overlap check (a filter: && isn't leakproof; the owner's plans at most)", "ex_plans_owner_id_dates", """
                //         select 1 from plans
                //         where owner_id = :owner and daterange(starts_on, ends_on, '[]') && daterange(:day, :day, '[]')""",
                //         OWNER_PLANS));
                // tenancy-row:else
                cell("an overlap check (the exclusion constraint's index)", "ex_plans_owner_id_dates", """
                        select 1 from plans
                        where owner_id = :owner and daterange(starts_on, ends_on, '[]') && daterange(:day, :day, '[]')""",
                        PAGE));
                // tenancy-row:end
    }

    @ParameterizedTest(name = "{0} ({1}): at most {3} rows")
    @MethodSource("cells")
    @DisplayName("Every cell of the plan reads has its index and reads at most its bound")
    void everyCellReadsAtMostItsBound(String cell, String index, String statement, int bound) {
        ReadCost cost = ReadCost.of(jdbc, transactionManager, statement, parameters);

        assertThat(ReadCost.indexExists(jdbc, index)).as(index).isTrue();
        assertThat(cost.rowsRead()).as(cost.plan()).isLessThanOrEqualTo(bound);
    }
}
