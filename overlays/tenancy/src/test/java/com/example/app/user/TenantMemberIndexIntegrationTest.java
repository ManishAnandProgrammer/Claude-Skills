package com.example.app.user;

import com.example.app.support.IntegrationTest;
import com.example.app.support.ReadCost;
import com.example.app.user.domain.Role;
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
 * Every cell of the members list has its index and reads at most its bound (crud-gen tests.md row 28), with
 * the statements of {@code TenantMembershipRepositoryImpl} (its select list and join). One tenant's 300
 * members, one in a hundred an ADMIN, sit among three tenants of 2,000.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantMemberIndexIntegrationTest extends IntegrationTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static final String MEMBER_ROWS = """
            select u.id, u.email, u.full_name, m.role, m.version, m.created_at, m.updated_at, m.created_by, m.updated_by
              from tenant_memberships m
              join users u on u.id = m.user_id""";

    private static final int TWO_PAGES = 40;
    private static final int TENANT_MEMBERS = 300;

    private UUID tenantId;
    private List<UUID> someMembers;

    @BeforeAll
    void seedOneTenantAmongBiggerOnes() throws Exception {
        tenantId = newTenant().id();
        List<UUID> others = List.of(newTenant().id(), newTenant().id(), newTenant().id());
        // Twenty rounds of joins, the tenant's then the others': rows reach the heap in id order, as in production.
        for (int round = 0; round < 20; round++) {
            seedMembers(tenantId, TENANT_MEMBERS / 20);
            for (UUID other : others) {
                seedMembers(other, 2000 / 20);
            }
        }
        someMembers = jdbc.queryForList("select user_id from tenant_memberships where tenant_id = ? limit 3", UUID.class, tenantId);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("set local default_statistics_target = 1000");
            jdbc.execute("analyze tenant_memberships, users");
        });
    }

    /** {@code count} more accounts joining the tenant now, one in a hundred an ADMIN: UUIDv7 ids and clock times. */
    private void seedMembers(UUID tenant, int count) {
        String marker = UUID.randomUUID().toString();
        sql("""
                insert into users (id, email, full_name, password_hash, role, created_at, updated_at, version)
                select uuidv7(), 'seeded-' || n || '-' || ? || '@example.com', 'Seeded', 'not-a-hash', 'USER',
                       now(), now(), 0
                  from generate_series(1, ?) n
                """, marker, count);
        sql("""
                insert into tenant_memberships (id, tenant_id, user_id, role, created_at, updated_at, version)
                select uuidv7(), ?, u.id,
                       case when row_number() over (order by u.id) % 100 = 0 then 'ADMIN' else 'USER' end,
                       clock_timestamp(), now(), 0
                  from users u
                 where u.email like 'seeded-%-' || ? || '@example.com'
                """, tenant, marker);
    }

    private static Arguments cell(String name, String index, String statement, int bound) {
        return Arguments.of(name, index, statement, bound);
    }

    /**
     * Every order, a later page, every role value under every order, the search and the ids lookup. Each row
     * is a membership and its account (probed by id), so a page reads twice its rows.
     */
    static Stream<Arguments> cells() {
        List<Arguments> cells = new ArrayList<>();
        for (String direction : List.of("desc", "asc")) {
            String order = " order by m.created_at " + direction + ", m.id " + direction;
            cells.add(cell("members, " + direction, "idx_tenant_memberships_tenant_id_created_at_id",
                    MEMBER_ROWS + " where m.tenant_id = :tenant" + order + " limit 20", 2 * TWO_PAGES));
            cells.add(cell("members, " + direction + ", a later page", "idx_tenant_memberships_tenant_id_created_at_id",
                    MEMBER_ROWS + " where m.tenant_id = :tenant" + order + " limit 20 offset 40", 2 * (40 + TWO_PAGES)));
            for (Role role : Role.values()) {
                cells.add(cell("members " + role + ", " + direction, "idx_tenant_memberships_tenant_id_role_created_at_id",
                        MEMBER_ROWS + " where m.tenant_id = :tenant and m.role = :role" + role + order + " limit 20", 2 * TWO_PAGES));
            }
            // query is a contains search on the email, no btree serves it: it reads the tenant's members (a team: the
            // seeded ones and the tenant's owner), no more.
            cells.add(cell("members matching a query, " + direction + " (the tenant's members at most)",
                    "idx_tenant_memberships_tenant_id_created_at_id",
                    MEMBER_ROWS + " where m.tenant_id = :tenant and strpos(lower(u.email), :query) > 0" + order + " limit 20",
                    2 * (TENANT_MEMBERS + 1)));
            // The ids filter under every order: the asked memberships through their key, then sorted.
            cells.add(cell("members by account ids, " + direction, "uq_tenant_memberships_user_id_tenant_id",
                    MEMBER_ROWS + " where m.tenant_id = :tenant and m.user_id in (:ids)" + order + " limit 20", 2 * TWO_PAGES));
        }
        return cells.stream();
    }

    @ParameterizedTest(name = "{0} ({1}): at most {3} rows")
    @MethodSource("cells")
    @DisplayName("Every cell of the members list has its index and reads at most its bound")
    void everyCellReadsAtMostItsBound(String cell, String index, String statement, int bound) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("tenant", tenantId)
                .addValue("ids", someMembers)
                .addValue("query", "matches nothing");
        // Every role bound as a parameter, as the repository binds it.
        for (Role role : Role.values()) {
            parameters.addValue("role" + role, role.name());
        }

        ReadCost cost = ReadCost.of(jdbc, transactionManager, statement, parameters);

        assertThat(ReadCost.indexExists(jdbc, index)).as(index).isTrue();
        assertThat(cost.rowsRead()).as(cost.plan()).isLessThanOrEqualTo(bound);
    }
}
