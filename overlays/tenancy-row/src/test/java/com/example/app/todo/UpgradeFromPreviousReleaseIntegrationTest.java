package com.example.app.todo;

import com.example.app.support.IntegrationTest;
import com.example.app.support.QueryPlan;
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
 * of the tenant lifecycle x seeding matrix (house-review references/tenancy-matrices.md) a fresh database
 * can't reach: a changeset over tenants that already hold rows, in every status. Runs only on such a run: verify.sh sets
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
    @DisplayName("""
            On the upgraded database (0029 and 0030 recorded) the seeded tenants keep every todo and requester, the \
            member who left among them""")
    void theSeededTenantsKeepTheirRows() throws SQLException {
        // The owner's table: the application role can't read it.
        assertThat(textInTenant(ALPHA, "select string_agg(id, ',') from databasechangelog"))
                .contains("0029-newest-by-created-at", "0030-platform-list-indexes");
        assertThat(inTenant(ALPHA, "select count(*) from todos where title like 'Upgrade todo %'")).isEqualTo(20 * 60 + 2 * 5 + 5);
        assertThat(inTenant(ALPHA, "select count(*) from todos where requester_id = '" + LEFT + "'")).isEqualTo(20 * 6);
        assertThat(inTenant(ALPHA, "select count(*) from todos where owner_id = '" + LEFT + "'"))
                .as("a member who left keeps their todos").isEqualTo(5);
    }

    @Test
    @DisplayName("""
            The upgrade changed no account and no staff member: the suspended account still SUSPENDED, the others \
            ACTIVE and signing in; the deactivated staff member still deactivated, the other active""")
    void theSeededAccountsAndStaffKeepTheirStatus() throws Exception {
        assertThat(jdbc.queryForObject("select count(*) from users where email like 'upgrade-%' and status <> 'ACTIVE'", Integer.class))
                .isOne();
        assertThat(jdbc.queryForObject("select status from users where email = 'upgrade-rho@example.com'", String.class))
                .isEqualTo("SUSPENDED");
        assertThat(jdbc.queryForObject("select count(*) from platform_users where email = 'upgrade-staff@example.com' and deactivated_at is null",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from platform_users where email = 'upgrade-gone-staff@example.com' and deactivated_at is not null",
                Integer.class)).isEqualTo(1);
        tokenOf("upgrade-heavy@example.com");
    }

    @Test
    @DisplayName("On the upgraded database, the seeded owner's OPEN page and newest page are planned on their indexes")
    void theUpgradedIndexesServeTheSeededOwner() {
        MapSqlParameterSource owner = new MapSqlParameterSource("owner", HEAVY).addValue("tenant", ALPHA);
        String open = QueryPlan.explain(jdbc, transactionManager,
                "select id from todos where owner_id = :owner and tenant_id = :tenant and status = 'OPEN' order by due_sort, id limit 21", owner);
        String newest = QueryPlan.explain(jdbc, transactionManager,
                "select id from todos where owner_id = :owner and tenant_id = :tenant order by created_at desc, id desc limit 21", owner);

        assertThat(QueryPlan.indexesScanned(open)).as(open).contains("idx_todos_owner_id_status_due_sort_id");
        assertThat(QueryPlan.indexesScanned(newest)).as(newest).contains("idx_todos_owner_id_created_at_id");
    }

    // roles:begin
    @Test
    @DisplayName("""
            The roles changeset gave every seeded tenant, whatever its status, its built-in roles, made each \
            membership's role its member's assignment of the tenant's copy, for good, and dropped the role columns""")
    void eachSeededMembershipsRoleBecameAnAssignment() throws SQLException {
        for (UUID tenant : TENANTS) {
            assertThat(inTenant(tenant, "select count(*) from roles where built_in in ('USER', 'ADMIN')")).as(tenant.toString()).isEqualTo(2);
        }
        String assignments = """
                select string_agg(a.user_id || ':' || r.built_in, ',' order by a.user_id)
                  from user_roles a join roles r on r.id = a.role_id and r.tenant_id = a.tenant_id
                 where a.user_id::text like '01890000-%' and a.valid_until is null""";
        assertThat(textInTenant(ALPHA, assignments))
                .isEqualTo("01890000-0000-7000-8000-000000000001:ADMIN,01890000-0000-7000-8000-000000000002:USER");
        assertThat(textInTenant(SIGMA, assignments)).isEqualTo("01890000-0000-7000-8000-000000000006:ADMIN");
        assertThat(textInTenant(PI, assignments)).isEqualTo("01890000-0000-7000-8000-000000000007:ADMIN");
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.columns
                 where table_name in ('users', 'tenant_memberships') and column_name = 'role'""", Integer.class)).isZero();
    }

    // roles:end
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
    @DisplayName("Every tenant, whatever its status, keeps its own numbers 1 to n and one counter after its highest")
    void everyTenantKeepsItsNumbers() throws SQLException {
        for (UUID tenant : TENANTS) {
            assertThat(inTenant(tenant, "select count(*) from counters where key = 'TODO_NUMBER'")).as("the counter of " + tenant).isOne();
            assertThat(inTenant(tenant, "select count(distinct number) from todos"))
                    .as("the numbers of " + tenant).isEqualTo(inTenant(tenant, "select count(*) from todos"));
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
        // roles:begin
        // The backfill's built-in roles kept, none copied twice, and the owner's ADMIN once, for good.
        assertThat(inTenant(PI, "select count(*) from roles where built_in = 'USER'")).isOne();
        assertThat(inTenant(PI, "select count(*) from roles where built_in = 'ADMIN'")).isOne();
        assertThat(inTenant(PI, """
                select count(*) from user_roles a join roles r on r.id = a.role_id and r.tenant_id = a.tenant_id
                 where a.user_id = '01890000-0000-7000-8000-000000000007' and r.built_in = 'ADMIN' and a.valid_until is null""")).isOne();
        // roles:end
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

    @Test
    @DisplayName("After 0033 every seeded tenant's plans are PLANNED without a done day")
    void theSeededPlansArePlanned() throws Exception {
        long seeded = 0;
        for (UUID tenant : TENANTS) {
            seeded += inTenant(tenant, "select count(*) from plans");
            assertThat(inTenant(tenant, "select count(*) from plans where status <> 'PLANNED' or done_on is not null")).isZero();
        }
        assertThat(seeded).as("the seeded plans").isPositive();
    }
}
