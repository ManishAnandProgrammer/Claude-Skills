package com.example.app.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Map;

/**
 * Tenancy rests on the pool's role being a plain role of the database:
 * <ul>
 *   <li>not a superuser and without {@code BYPASSRLS} (either skips row-level security), nor a member of
 *       a role that is: {@code SET ROLE} reaches it;</li>
 *   <li>owning no table, nor a member of a table's owner: an owner can lift {@code FORCE}, drop a policy
 *       or grant itself more, and {@code GRANT <owner> TO <app>}, a common quick fix for a missing grant,
 *       hands all of that over while every catalogue column of the pool's role still looks plain;</li>
 *   <li>unable to write {@code platform_users} (0015): with that right, one SQL injection creates a staff
 *       account that may impersonate every tenant.</li>
 * </ul>
 * Checked once at startup, after the migrations; a violation stops the application, since nothing else
 * would notice.
 */
@Component
@DependsOn("liquibase")
class PoolRoleCheck implements InitializingBean {

    /** {@code pg_has_role(…, 'MEMBER')} is true for the role itself and every role it reaches, directly or not. */
    static final String POOL_ROLE = """
            select exists (select 1 from pg_roles r
                            where pg_has_role(current_user, r.oid, 'MEMBER')
                              and (r.rolsuper or r.rolbypassrls)) as privileged,
                   (select count(*) from pg_class c
                     where c.relkind in ('r', 'p')
                       and pg_has_role(current_user, c.relowner, 'MEMBER')) as owned_tables,
                   has_table_privilege('platform_users', 'INSERT, UPDATE, DELETE') as writes_staff""";

    private final JdbcTemplate jdbc;

    PoolRoleCheck(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void afterPropertiesSet() {
        Map<String, Object> role = jdbc.queryForMap(POOL_ROLE);
        check((Boolean) role.get("privileged"), ((Number) role.get("owned_tables")).longValue(), (Boolean) role.get("writes_staff"));
    }

    static void check(boolean privileged, long ownedTables, boolean writesStaff) {
        if (privileged) {
            throw new IllegalStateException("""
                    The pool's role (DB_USER) is a superuser or has BYPASSRLS, itself or through a role it is a \
                    member of: tenancy needs a plain role (docs/tenancy-runbook.md, "Database roles")""");
        }
        if (ownedTables > 0) {
            throw new IllegalStateException("""
                    The pool's role (DB_USER) owns %d table(s), itself or through a role it is a member of: \
                    migrations must run as the owner role (DB_OWNER_USER), and the pool's role must own nothing \
                    nor be granted the owner role""".formatted(ownedTables));
        }
        if (writesStaff) {
            throw new IllegalStateException("""
                    The pool's role (DB_USER) can write platform_users: staff accounts are the owner role's to \
                    write (docs/tenancy-runbook.md, section 2); revoke INSERT, UPDATE and DELETE on it""");
        }
    }
}
