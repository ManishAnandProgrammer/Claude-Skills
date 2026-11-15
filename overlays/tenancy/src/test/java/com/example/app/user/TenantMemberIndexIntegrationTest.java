package com.example.app.user;

import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The members list reads its intended index in each order and under the role filter: the index named in
 * the plan, the tenant in the index condition, no sort step. The statements have the shape of
 * {@code TenantMembershipRepositoryImpl}'s query (its select list and join). One tenant gets a few hundred
 * members among bigger tenants and the table is analysed; whole-table scans are switched off, because on
 * a test database's few rows they would win on cost and hide a missing index.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantMemberIndexIntegrationTest extends IntegrationTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static final String MEMBER_ROWS = """
            select u.id, u.email, u.full_name, m.role, m.version, m.created_at, m.updated_at, m.created_by, m.updated_by
              from tenant_memberships m
              join users u on u.id = m.user_id""";

    private UUID tenantId;
    private List<UUID> someMembers;

    @BeforeAll
    void seedOneTenantAmongBiggerOnes() throws Exception {
        tenantId = newTenant().id();
        seedMembers(tenantId, 300);
        for (int other = 0; other < 3; other++) {
            seedMembers(newTenant().id(), 2000);
        }
        someMembers = jdbc.queryForList("select user_id from tenant_memberships where tenant_id = ? limit 3", UUID.class, tenantId);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> jdbc.execute("analyze tenant_memberships, users"));
    }

    /** {@code count} accounts in the tenant, one in a hundred an ADMIN. */
    private void seedMembers(UUID tenant, int count) {
        String marker = UUID.randomUUID().toString();
        sql("""
                insert into users (id, email, full_name, password_hash, role, created_at, updated_at, version)
                select gen_random_uuid(), 'seeded-' || n || '-' || ? || '@example.com', 'Seeded', 'not-a-hash', 'USER',
                       now(), now(), 0
                  from generate_series(1, ?) n
                """, marker, count);
        sql("""
                insert into tenant_memberships (id, tenant_id, user_id, role, created_at, updated_at, version)
                select gen_random_uuid(), ?, u.id,
                       case when row_number() over (order by u.id) % 100 = 0 then 'ADMIN' else 'USER' end,
                       now(), now(), 0
                  from users u
                 where u.email like 'seeded-%-' || ? || '@example.com'
                """, tenant, marker);
    }

    private String plan(String statement, MapSqlParameterSource parameters) {
        List<String> planLines = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.execute("set local enable_seqscan = off");
            jdbc.execute("set local enable_bitmapscan = off");
            return new NamedParameterJdbcTemplate(jdbc).queryForList("explain " + statement, parameters, String.class);
        });
        return String.join("\n", planLines);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"desc", "asc"})
    @DisplayName("NEWEST and OLDEST walk idx_tenant_memberships_tenant_id_id in order, a later page too")
    void ordersReadTheTenantIndex(String direction) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("tenant", tenantId);
        for (String page : List.of("limit 20", "limit 20 offset 40")) {
            String plan = plan(MEMBER_ROWS + " where m.tenant_id = :tenant order by m.id " + direction + " " + page, parameters);

            assertThat(plan).as(plan).contains("idx_tenant_memberships_tenant_id_id").doesNotContain("Sort");
            assertThat(plan).as(plan).containsPattern("Index Cond: [^\\n]*tenant_id = ");
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"desc", "asc"})
    @DisplayName("The ADMIN filter walks idx_tenant_memberships_tenant_id_role_id in order, in both orders")
    void theRoleFilterReadsTheRoleIndex(String direction) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("tenant", tenantId).addValue("role", "ADMIN");

        String plan = plan(MEMBER_ROWS + " where m.tenant_id = :tenant and m.role = :role order by m.id " + direction + " limit 20",
                parameters);

        assertThat(plan).as(plan).contains("idx_tenant_memberships_tenant_id_role_id").doesNotContain("Sort");
        assertThat(plan).as(plan).containsPattern("Index Cond: [^\\n]*role = ");
    }

    @Test
    @DisplayName("The USER filter (most members) reads a tenant index in order, never a sort or the whole table")
    void theCommonRoleStillReadsInOrder() {
        MapSqlParameterSource parameters = new MapSqlParameterSource("tenant", tenantId).addValue("role", "USER");

        String plan = plan(MEMBER_ROWS + " where m.tenant_id = :tenant and m.role = :role order by m.id desc limit 20", parameters);

        assertThat(plan).as(plan).containsPattern("idx_tenant_memberships_tenant_id(_role)?_id").doesNotContain("Sort", "Seq Scan");
    }

    @Test
    @DisplayName("The ids lookup probes the members by account id, never the tenant's whole list")
    void theIdsLookupProbesByAccount() {
        MapSqlParameterSource parameters = new MapSqlParameterSource("tenant", tenantId).addValue("ids", someMembers);

        String plan = plan(MEMBER_ROWS + " where m.tenant_id = :tenant and m.user_id in (:ids) order by m.id desc limit 20",
                parameters);

        assertThat(plan).as(plan).contains("uq_tenant_memberships_user_id_tenant_id").doesNotContain("Seq Scan");
        assertThat(plan).as(plan).containsPattern("Index Cond: [^\\n]*user_id = ");
    }
}
