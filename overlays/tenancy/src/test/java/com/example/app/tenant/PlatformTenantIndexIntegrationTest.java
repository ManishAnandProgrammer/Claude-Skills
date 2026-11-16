package com.example.app.tenant;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.app.support.IntegrationTest;
import com.example.app.support.ReadCost;
import com.example.app.support.WithoutTestTenant;
import com.example.app.tenant.api.TenantStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The staff's tenant list, a table without a scope column (crud-gen tests.md row 28, SKILL.md 4.1): every
 * cell under the generic plan pgjdbc's prepared statements end up with. A rare status (PENDING, SUSPENDED,
 * CLOSED, REJECTED) has a partial index per order ({@code WHERE status = '<VALUE>'}, changeset 0030), which the
 * plan matches only because the repository writes the status as a literal; ACTIVE, most of the register, walks
 * the order's own index.
 *
 * <p>The seed, 10,000 tenants written as the application writes them (UUIDv7 ids and clock times, in twenty
 * rounds): one in fifty PENDING among the newest two rounds (registrations waiting for staff) plus three old
 * ones still waiting, SUSPENDED, CLOSED and REJECTED (decisions taken later) only among the first fifteen
 * rounds, the rest ACTIVE. Which cells the seed makes read ten times their bound without their index, per cell:
 * every rare value's cell (without its partial index the order's index is walked through thousands of ACTIVE
 * tenants; the count reads the whole register); the ACTIVE cells can't be made expensive by any seed (the value
 * is most of the table, its order's index is right): they rest on the index's existence check. The list's
 * {@code query} (a contains search on the name) has no cell: the register is a bounded table, recorded (staff's
 * own, thousands of tenants at most; house-review DATA-10's convention for an unscoped search). Seeded tenants
 * are deleted afterwards.</p>
 */
@WithoutTestTenant
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlatformTenantIndexIntegrationTest extends IntegrationTest {

    private static final String TENANT_ROW = """
            select id, name, status, created_at, updated_at, created_by, updated_by, version
              from tenants""";
    private static final String SEEDED = "Seeded register ";
    private static final int PAGE = 20;
    private static final int TWO_PAGES = 2 * PAGE;
    /** The recent PENDING block: one in fifty of the newest two rounds' 500. */
    private static final int RECENT_PENDING = 20;
    private static final List<TenantStatus> RARE = List.of(TenantStatus.PENDING, TenantStatus.SUSPENDED,
            TenantStatus.CLOSED, TenantStatus.REJECTED);

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeAll
    void seedTheRegister() {
        for (int round = 0; round < 20; round++) {
            sql("""
                    insert into tenants (id, name, status, created_at, updated_at, version)
                    select uuidv7(), ? || gen_random_uuid(),
                           case when ? >= 18 and n % 50 = 0 then 'PENDING'
                                when ? = 0 and n <= 3 then 'PENDING'
                                when ? < 15 and n % 100 = 4 then 'SUSPENDED'
                                when ? < 15 and n % 50 = 5 then 'CLOSED'
                                when ? < 15 and n % 100 = 6 then 'REJECTED'
                                else 'ACTIVE' end,
                           clock_timestamp(), now(), 0
                      from generate_series(1, 500) n
                    """, SEEDED, round, round, round, round, round);
        }
        // The seed's own claim, checked before any cell relies on it: the old PENDING tenants are older than the recent block.
        Long olderThanTheBlock = jdbc.queryForObject("""
                select count(*) from tenants old
                 where old.name like ? and old.status = 'PENDING'
                   and old.created_at < (select min(created_at) from (select created_at from tenants
                                          where name like ? and status = 'PENDING' order by created_at desc limit ?) recent)""",
                Long.class, SEEDED + "%", SEEDED + "%", RECENT_PENDING);
        assertThat(olderThanTheBlock).isEqualTo(3);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("set local default_statistics_target = 1000");
            jdbc.execute("analyze tenants");
        });
    }

    @AfterAll
    void removeTheSeed() {
        sql("delete from tenants where name like ?", SEEDED + "%");
    }

    private static Arguments cell(String name, String index, String statement, List<?> values, int bound) {
        return Arguments.of(name, index, statement, values, bound);
    }

    private static String partialIndex(TenantStatus status, String order) {
        return "idx_tenants_" + status.name().toLowerCase(Locale.ROOT) + "_" + order;
    }

    /** As TenantRepositoryImpl writes them: the status a literal, the page's limit and offset bound ($1, $2). */
    static Stream<Arguments> cells() {
        List<Arguments> cells = new ArrayList<>();
        for (TenantStatus status : RARE) {
            String filter = " where status = '" + status.name() + "'";
            cells.add(cell(status + ", NAME", partialIndex(status, "name_id"),
                    TENANT_ROW + filter + " order by name, id limit $1 offset $2", List.of(PAGE, 0), TWO_PAGES));
            cells.add(cell(status + ", NEWEST", partialIndex(status, "created_at_id"),
                    TENANT_ROW + filter + " order by created_at desc, id desc limit $1 offset $2", List.of(PAGE, 0), TWO_PAGES));
            cells.add(cell(status + ", a full page's count (its own rows: bounded at run time)", partialIndex(status, "created_at_id"),
                    "select count(*) from tenants" + filter, List.of(), -1));
        }
        // Past the recent block: the page starts inside it and goes on into the old PENDING tenants. Walking the
        // order's index, it would read every ACTIVE tenant between them.
        cells.add(cell("PENDING, NEWEST, a page past the recent block", "idx_tenants_pending_created_at_id",
                TENANT_ROW + " where status = 'PENDING' order by created_at desc, id desc limit $1 offset $2",
                List.of(PAGE, RECENT_PENDING - 10), RECENT_PENDING - 10 + TWO_PAGES));
        // ACTIVE is most of the register: the order's own index, walked and filtered.
        cells.add(cell("ACTIVE, NAME (walked: most of the table)", "idx_tenants_name_id",
                TENANT_ROW + " where status = 'ACTIVE' order by name, id limit $1 offset $2", List.of(PAGE, 0), 2 * TWO_PAGES));
        cells.add(cell("ACTIVE, NEWEST (walked: most of the table)", "idx_tenants_created_at_id",
                TENANT_ROW + " where status = 'ACTIVE' order by created_at desc, id desc limit $1 offset $2", List.of(PAGE, 0),
                2 * TWO_PAGES));
        cells.add(cell("every status, NAME", "idx_tenants_name_id",
                TENANT_ROW + " order by name, id limit $1 offset $2", List.of(PAGE, 0), TWO_PAGES));
        cells.add(cell("every status, NEWEST, a later page", "idx_tenants_created_at_id",
                TENANT_ROW + " order by created_at desc, id desc limit $1 offset $2", List.of(PAGE, 40), 40 + TWO_PAGES));
        return cells.stream();
    }

    @ParameterizedTest(name = "{0} ({1})")
    @MethodSource("cells")
    @DisplayName("Under a generic plan, every cell of the tenant list has its index and reads at most its bound")
    void everyCellReadsAtMostItsBound(String cell, String index, String statement, List<?> values, int bound) {
        ReadCost cost = ReadCost.ofGenericPlan(jdbc, transactionManager, statement, values);
        // A count's bound is its value's rows, counted now: the register holds other classes' tenants too.
        long limit = bound >= 0 ? bound : jdbc.queryForObject(statement, Long.class);

        assertThat(ReadCost.indexExists(jdbc, index)).as(index).isTrue();
        assertThat(cost.rowsRead()).as(cost.plan()).isLessThanOrEqualTo(limit);
    }

    @Test
    @DisplayName("The list's statement names the status as a literal, the shape the partial indexes need")
    void theStatusIsALiteral() throws Exception {
        Logger sqlLog = (Logger) LoggerFactory.getLogger("org.hibernate.SQL");
        Level configured = sqlLog.getLevel();
        ListAppender<ILoggingEvent> statements = new ListAppender<>();
        statements.start();
        sqlLog.addAppender(statements);
        sqlLog.setLevel(Level.DEBUG);
        try {
            getAs(platformToken(), "/api/v1/platform/tenants?status=PENDING&sort=NEWEST").andExpect(status().isOk());
        } finally {
            sqlLog.setLevel(configured);
            sqlLog.detachAppender(statements);
        }

        assertThat(statements.list).extracting(ILoggingEvent::getFormattedMessage)
                .filteredOn(sql -> sql.contains("from tenants"))
                .isNotEmpty()
                .allMatch(sql -> sql.contains("='PENDING'") || sql.contains("= 'PENDING'"));
    }
}
