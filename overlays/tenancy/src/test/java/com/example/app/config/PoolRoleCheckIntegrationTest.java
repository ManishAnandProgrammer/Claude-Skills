package com.example.app.config;

import com.example.app.support.IntegrationTest;
import com.example.app.support.WithoutTestTenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PoolRoleCheck}'s query against the real roles: the test pool's role passes, and the grants that
 * would quietly undo the isolation are caught. Each change runs in a superuser's transaction that is
 * rolled back ({@code GRANT} and {@code SET ROLE} are transactional), so no other test sees it.
 */
@WithoutTestTenant
class PoolRoleCheckIntegrationTest extends IntegrationTest {

    @Autowired
    private PostgreSQLContainer postgres;

    @Value("${app.liquibase.username}")
    private String ownerRole;

    /**
     * The check's answer for the pool's role after {@code change} ({@code %s} names that role), read as the
     * role in the same transaction.
     */
    private Map<String, Object> poolRoleAfter(String change) throws SQLException {
        String appRole = jdbc.queryForObject("select current_user", String.class);
        String staffSchema = jdbc.queryForObject("""
                select n.nspname from pg_class c join pg_namespace n on n.oid = c.relnamespace
                 where c.oid = 'platform_users'::regclass""", String.class);
        try (Connection superuser = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            superuser.setAutoCommit(false);
            JdbcTemplate asSuperuser = new JdbcTemplate(new SingleConnectionDataSource(superuser, true));
            try {
                // The schema of platform_users first, so the change and the check resolve it as the pool does.
                asSuperuser.execute("set local schema '" + staffSchema + "'");
                if (!change.isEmpty()) {
                    asSuperuser.execute(change.formatted(appRole));
                }
                asSuperuser.execute("set local role " + appRole);
                return asSuperuser.queryForMap(PoolRoleCheck.POOL_ROLE);
            } finally {
                superuser.rollback();
            }
        }
    }

    @Test
    @DisplayName("The pool's role as migrated is plain: no privileged role, no owned table, no write on platform_users")
    void thePoolsRoleIsPlain() throws SQLException {
        assertThat(poolRoleAfter(""))
                .containsEntry("privileged", false)
                .containsEntry("owned_tables", 0L)
                .containsEntry("writes_staff", false);
    }

    @Test
    @DisplayName("The owner role granted to the pool's role is caught: it reaches every table's ownership")
    void anInheritedOwnerIsCaught() throws SQLException {
        Map<String, Object> role = poolRoleAfter("grant " + ownerRole + " to %s");

        assertThat((Long) role.get("owned_tables")).isPositive();
    }

    @Test
    @DisplayName("Membership in a BYPASSRLS role is caught, and so is a write right on platform_users granted again")
    void inheritedBypassAndStaffWritesAreCaught() throws SQLException {
        Map<String, Object> bypass = poolRoleAfter("""
                create role pool_check_bypass nologin bypassrls;
                grant pool_check_bypass to %s""");
        Map<String, Object> staff = poolRoleAfter("grant insert on platform_users to %s");

        assertThat(bypass).containsEntry("privileged", true);
        assertThat(staff).containsEntry("writes_staff", true);
    }
}
