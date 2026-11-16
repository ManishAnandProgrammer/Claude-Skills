package com.example.app.todo;

import com.example.app.common.tenancy.TenantSchemas;
import com.example.app.support.IntegrationTest;
import com.example.app.support.QueryPlan;
import com.example.app.support.WithoutTestTenant;
import com.example.app.tenant.service.TenantMigrations;
import com.example.app.tenant.service.TenantProvisioningJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Schema per tenant: what the deploy-time migrations did to a used database (support/PreviousReleaseDatabase
 * built it: the platform at the previous release with tenants in every status, each provisioned tenant's
 * schema at that release with its rows; crud-gen tests.md, *Upgrading a used database*). TenantMigrations
 * brings the schemas up as a deploy does; the cells of the tenant lifecycle x seeding matrix
 * (docs/design/tenancy.md) a fresh database can't reach. Runs only on such a run: verify.sh sets
 * {@code app.test.upgrade.previous-release}.
 */
@WithoutTestTenant
@EnabledIfSystemProperty(named = "app.test.upgrade.previous-release", matches = "\\d+")
class UpgradeFromPreviousReleaseIntegrationTest extends IntegrationTest {

    private static final UUID ALPHA = UUID.fromString("01890000-0000-7000-8100-00000000000a");
    private static final UUID SIGMA = UUID.fromString("01890000-0000-7000-8100-00000000000c");
    private static final UUID PI = UUID.fromString("01890000-0000-7000-8100-00000000000d");
    private static final UUID RHO = UUID.fromString("01890000-0000-7000-8100-00000000000e");
    /** The seeded tenants with a schema: ACTIVE twice, SUSPENDED, CLOSED. */
    private static final List<UUID> PROVISIONED = List.of(ALPHA, UUID.fromString("01890000-0000-7000-8100-00000000000b"), SIGMA,
            UUID.fromString("01890000-0000-7000-8100-00000000000f"));
    private static final UUID HEAVY = UUID.fromString("01890000-0000-7000-8000-000000000001");
    private static final UUID LEFT = UUID.fromString("01890000-0000-7000-8000-000000000003");

    @Autowired
    private TenantMigrations tenantMigrations;

    @Autowired
    private TenantProvisioningJob provisioning;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    @LiquibaseDataSource
    private DataSource ownerDataSource;

    /** The schemas as a deploy leaves them: the run migrates what is missing, so a second one changes nothing. */
    private JdbcTemplate migratedOwner() {
        assertThat(tenantMigrations.run().failed()).isEmpty();
        return new JdbcTemplate(ownerDataSource);
    }

    /** A seeded account's access token: the seed stores no real password hash, so the test gives it one. */
    private String tokenOf(String email) throws Exception {
        sql("update users set password_hash = ? where email = ?", passwordEncoder.encode(PASSWORD), email);
        return read(login(email, PASSWORD).andExpect(status().isOk()), "$.response.accessToken");
    }

    private String firstListOf(JdbcTemplate owner, UUID tenantId, UUID ownerId) {
        return owner.queryForObject("select id::text from %s.todo_lists where owner_id = ? order by id limit 1"
                .formatted(TenantSchemas.of(tenantId)), String.class, ownerId);
    }

    @Test
    @DisplayName("The deploy brought every seeded schema up: the new changesets recorded, no todo lost; PENDING and REJECTED have none")
    void theDeployMigratedEverySeededSchema() {
        JdbcTemplate owner = migratedOwner();

        for (UUID tenant : PROVISIONED) {
            String schema = TenantSchemas.of(tenant);
            assertThat(owner.queryForList("select id from %s.databasechangelog".formatted(schema), String.class))
                    .as(schema).contains("0029-newest-by-created-at");
            assertThat(owner.queryForObject("select status from platform.tenant_migrations where tenant_id = ?", String.class, tenant))
                    .isEqualTo("MIGRATED");
        }
        assertThat(owner.queryForObject("select count(*) from %s.todos where title like 'Upgrade todo %%'"
                .formatted(TenantSchemas.of(ALPHA)), Integer.class)).isEqualTo(20 * 60 + 5 + 5);
        assertThat(owner.queryForObject("select count(*) from %s.todos where owner_id = ?".formatted(TenantSchemas.of(ALPHA)),
                Integer.class, LEFT)).as("a member who left keeps their todos").isEqualTo(5);
        assertThat(owner.queryForObject("select count(*) from %s.todos where requester_id = ?".formatted(TenantSchemas.of(ALPHA)),
                Integer.class, LEFT)).as("the todos the member who left asked for still name them").isEqualTo(20 * 6);
        assertThat(owner.queryForList("select id from platform.databasechangelog", String.class))
                .contains("0030-platform-list-indexes");
        for (UUID unprovisioned : List.of(PI, RHO)) {
            assertThat(owner.queryForObject("select count(*) from pg_namespace where nspname = ?", Integer.class,
                    TenantSchemas.of(unprovisioned))).isZero();
        }
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
        migratedOwner();

        MapSqlParameterSource owner = new MapSqlParameterSource("owner", HEAVY);
        String open = QueryPlan.explain(jdbc, transactionManager,
                "select id from todos where owner_id = :owner and status = 'OPEN' order by due_sort, id limit 21", owner);
        String newest = QueryPlan.explain(jdbc, transactionManager,
                "select id from todos where owner_id = :owner order by created_at desc, id desc limit 21", owner);

        assertThat(QueryPlan.indexesScanned(open)).as(open).contains("idx_todos_owner_id_status_due_sort_id");
        assertThat(QueryPlan.indexesScanned(newest)).as(newest).contains("idx_todos_owner_id_created_at_id");
    }

    @Test
    @DisplayName("A member who left can't be named on a new todo: 404 TENANT_MEMBER_NOT_FOUND")
    void aMemberWhoLeftIsNotNamed() throws Exception {
        JdbcTemplate owner = migratedOwner();

        postAs(tokenOf("upgrade-heavy@example.com"), "/api/v1/todos", """
                {"todoListId": "%s", "title": "Asked by someone gone", "priority": "LOW", "requesterId": "%s"}
                """.formatted(firstListOf(owner, ALPHA, HEAVY), LEFT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TENANT_MEMBER_NOT_FOUND"));
    }
    // metadata:begin

    @Test
    @DisplayName("Every seeded schema keeps its own numbers 1 to n and its counter after the highest")
    void everySchemaKeepsItsNumbers() {
        JdbcTemplate owner = migratedOwner();

        for (UUID tenant : PROVISIONED) {
            String schema = TenantSchemas.of(tenant);
            assertThat(owner.queryForObject("select count(distinct number) from %s.todos".formatted(schema), Integer.class))
                    .as(schema).isEqualTo(owner.queryForObject("select count(*) from %s.todos".formatted(schema), Integer.class));
            assertThat(owner.queryForObject("select last_value from %s.counters where key = 'TODO_NUMBER'".formatted(schema), Long.class))
                    .as(schema).isEqualTo(owner.queryForObject("select coalesce(max(number), 0) from %s.todos".formatted(schema), Long.class));
        }
    }

    @Test
    @DisplayName("The PENDING tenant, approved after the upgrade, is provisioned with the whole changelog and numbers its first todo #1")
    void thePendingTenantIsProvisionedAfterTheUpgrade() throws Exception {
        migratedOwner();
        postAs(platformToken(), "/api/v1/platform/tenants/" + PI + "/approve", "").andExpect(status().isAccepted());

        provisioning.provisionPending();

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
        JdbcTemplate owner = migratedOwner();
        String staff = platformToken();
        Integer version = read(getAs(staff, "/api/v1/platform/tenants/" + SIGMA).andExpect(status().isOk()), "$.response.version");
        patchAs(staff, "/api/v1/platform/tenants/" + SIGMA + "/status", """
                {"status": "ACTIVE", "version": %d}
                """.formatted(version)).andExpect(status().isOk());

        postAs(tokenOf("upgrade-sigma@example.com"), "/api/v1/todos", """
                {"todoListId": "%s", "title": "After the upgrade", "priority": "LOW"}
                """.formatted(firstListOf(owner, SIGMA, UUID.fromString("01890000-0000-7000-8000-000000000006"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.number").value(31));
    }
    // metadata:end
}
