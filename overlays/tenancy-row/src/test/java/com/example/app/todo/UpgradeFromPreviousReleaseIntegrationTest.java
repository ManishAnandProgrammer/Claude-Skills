package com.example.app.todo;

import com.example.app.common.tenancy.TenantScope;
import com.example.app.support.IntegrationTest;
import com.example.app.support.ReadCost;
import com.example.app.support.WithoutTestTenant;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Row mode: what the changesets after the previous release did to a used database (support/
 * PreviousReleaseDatabase built it: that release's changesets, then db/upgrade/previous-release-seed.sql
 * with tenants in every status, then the rest; crud-gen tests.md, *Upgrading a used database*). The cells
 * of the tenant lifecycle x seeding matrix (docs/design/tenancy.md) a fresh database can't reach: a
 * backfill over tenants that already hold rows, in every status. Runs only on such a run: verify.sh sets
 * {@code app.test.upgrade.previous-release}.
 */
@WithoutTestTenant
@EnabledIfSystemProperty(named = "app.test.upgrade.previous-release", matches = "\\d+")
class UpgradeFromPreviousReleaseIntegrationTest extends IntegrationTest {

    private static final UUID ALPHA = UUID.fromString("01890000-0000-7000-8100-00000000000a");
    private static final UUID SIGMA = UUID.fromString("01890000-0000-7000-8100-00000000000c");
    private static final UUID PI = UUID.fromString("01890000-0000-7000-8100-00000000000d");
    /** Every seeded tenant: ACTIVE twice, SUSPENDED, PENDING, REJECTED, CLOSED. */
    private static final List<UUID> TENANTS = List.of(ALPHA, UUID.fromString("01890000-0000-7000-8100-00000000000b"), SIGMA, PI,
            UUID.fromString("01890000-0000-7000-8100-00000000000e"), UUID.fromString("01890000-0000-7000-8100-00000000000f"));
    private static final UUID HEAVY = UUID.fromString("01890000-0000-7000-8000-000000000001");
    private static final UUID LEFT = UUID.fromString("01890000-0000-7000-8000-000000000003");

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @Value("${app.liquibase.username}")
    private String ownerUser;

    @Value("${app.liquibase.password}")
    private String ownerPassword;

    /** A seeded account's access token: the seed stores no real password hash, so the test gives it one. */
    private String tokenOf(String email) throws Exception {
        sql("update users set password_hash = ? where email = ?", passwordEncoder.encode(PASSWORD), email);
        return read(login(email, PASSWORD).andExpect(status().isOk()), "$.response.accessToken");
    }

    /** {@code query}'s single number in {@code tenantId}, read as the owner told that tenant (the policy binds it too). */
    private long inTenant(UUID tenantId, String query) throws SQLException {
        return Long.parseLong(textInTenant(tenantId, "select (" + query + ")::text"));
    }

    private String firstListOf(UUID tenantId, UUID ownerId) throws SQLException {
        return textInTenant(tenantId, "select id::text from todo_lists where owner_id = '%s' order by id limit 1".formatted(ownerId));
    }

    private String textInTenant(UUID tenantId, String query) throws SQLException {
        try (Connection owner = DriverManager.getConnection(dataSource.unwrap(HikariDataSource.class).getJdbcUrl(), ownerUser, ownerPassword)) {
            JdbcTemplate asOwner = new JdbcTemplate(new SingleConnectionDataSource(owner, true));
            asOwner.queryForObject("select set_config('app.tenant_id', ?, false)", String.class, tenantId.toString());
            return asOwner.queryForObject(query, String.class);
        }
    }

    @Test
    @DisplayName("The new release's changesets ran over the seeded tenants: no todo lost, none of them names a requester")
    void theNewChangesetsRanOverTheSeededTenants() throws SQLException {
        // The owner's table: the application role can't read it.
        assertThat(textInTenant(ALPHA, "select string_agg(id, ',') from databasechangelog"))
                .contains("0024-tags-status-newest", "0025-todos-requester", "0026-todo-numbers");
        assertThat(inTenant(ALPHA, "select count(*) from todos where title like 'Upgrade todo %'")).isEqualTo(20 * 60 + 2 * 5 + 5);
        assertThat(inTenant(ALPHA, "select count(*) from todos where requester_id is not null and title like 'Upgrade todo %'")).isZero();
        assertThat(inTenant(ALPHA, "select count(*) from todos where owner_id = '" + LEFT + "'"))
                .as("a member who left keeps their todos").isEqualTo(5);
    }

    @Test
    @DisplayName("A page of the seeded owner's OPEN todos, the rare status, still reads at most two pages")
    void theIndexBoundsHoldOnTheSeededRows() {
        ReadCost cost = TenantScope.callAs(ALPHA, () -> ReadCost.of(jdbc, transactionManager, """
                select id from todos
                 where owner_id = :owner and tenant_id = :tenant and status = 'OPEN' order by due_sort, id limit 21""",
                new MapSqlParameterSource("owner", HEAVY).addValue("tenant", ALPHA)));

        assertThat(cost.rowsRead()).as(cost.plan()).isLessThanOrEqualTo(42);
    }

    @Test
    @DisplayName("A member who left can't be named on a new todo: 404 TENANT_MEMBER_NOT_FOUND")
    void aMemberWhoLeftIsNotNamed() throws Exception {
        String heavy = tokenOf("upgrade-heavy@example.com");

        postAs(heavy, "/api/v1/todos", """
                {"todoListId": "%s", "title": "Asked by someone gone", "priority": "LOW", "requesterId": "%s"}
                """.formatted(firstListOf(ALPHA, HEAVY), LEFT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TENANT_MEMBER_NOT_FOUND"));
    }
    // metadata:begin

    @Test
    @DisplayName("Every tenant, whatever its status, numbers its own todos 1 to n in the order they were created, its counter after the highest")
    void everyTenantIsNumberedOnItsOwn() throws SQLException {
        for (UUID tenant : TENANTS) {
            assertThat(inTenant(tenant, "select count(*) from counters where key = 'TODO_NUMBER'")).as("the counter of " + tenant).isOne();
            assertThat(inTenant(tenant, """
                    select count(*) from (select number, row_number() over (order by id) as expected from todos
                                           where title like 'Upgrade todo %') seeded
                     where number <> expected""")).as("the numbers of " + tenant).isZero();
            assertThat(inTenant(tenant, "select last_value from counters where key = 'TODO_NUMBER'"))
                    .as("the counter of " + tenant).isEqualTo(inTenant(tenant, "select coalesce(max(number), 0) from todos"));
        }
    }

    @Test
    @DisplayName("The PENDING tenant, approved after the upgrade, gets its seeds, keeps its counter, and numbers its first todo #1")
    void thePendingTenantIsActivatedAfterTheUpgrade() throws Exception {
        postAs(platformToken(), "/api/v1/platform/tenants/" + PI + "/approve", "")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.response.status").value("ACTIVE"));

        // media:begin
        assertThat(inTenant(PI, "select count(*) from upload_policies where purpose = 'TODO_ATTACHMENT'")).isOne();
        // media:end
        assertThat(inTenant(PI, "select count(*) from configuration_metadata where key = 'TODO_LIST_DEFAULTS'")).isOne();
        assertThat(inTenant(PI, "select count(*) from counters where key = 'TODO_NUMBER'")).isOne();
        String owner = tokenOf("upgrade-pi@example.com");
        String listId = read(postAs(owner, "/api/v1/todo-lists", """
                {"name": "First"}
                """).andExpect(status().isCreated()), "$.response.id");
        postAs(owner, "/api/v1/todos", """
                {"todoListId": "%s", "title": "First", "priority": "LOW"}
                """.formatted(listId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.number").value(1));
    }

    @Test
    @DisplayName("The SUSPENDED tenant, reactivated after the upgrade, numbers its next todo after its 30")
    void theSuspendedTenantGoesOnAfterItsHighestNumber() throws Exception {
        String staff = platformToken();
        Integer version = read(getAs(staff, "/api/v1/platform/tenants/" + SIGMA).andExpect(status().isOk()), "$.response.version");
        patchAs(staff, "/api/v1/platform/tenants/" + SIGMA + "/status", """
                {"status": "ACTIVE", "version": %d}
                """.formatted(version)).andExpect(status().isOk());

        postAs(tokenOf("upgrade-sigma@example.com"), "/api/v1/todos", """
                {"todoListId": "%s", "title": "After the upgrade", "priority": "LOW"}
                """.formatted(firstListOf(SIGMA, UUID.fromString("01890000-0000-7000-8000-000000000006"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.number").value(31));
    }
    // metadata:end
}
