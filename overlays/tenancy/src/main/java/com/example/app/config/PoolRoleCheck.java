package com.example.app.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Map;

/**
 * Tenancy rests on the pool's role being a plain role of the database: not a superuser and without
 * {@code BYPASSRLS} (either skips row-level security), and owning no table (an owner can lift
 * {@code FORCE}, drop a policy or grant itself more). Checked once at startup, after the migrations; a
 * violation stops the application, since nothing else would notice.
 */
@Component
@DependsOn("liquibase")
class PoolRoleCheck implements InitializingBean {

    private static final String POOL_ROLE = """
            select r.rolsuper or r.rolbypassrls as privileged,
                   (select count(*) from pg_class c where c.relowner = r.oid and c.relkind in ('r', 'p')) as owned_tables
              from pg_roles r
             where r.rolname = current_user""";

    private final JdbcTemplate jdbc;

    PoolRoleCheck(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void afterPropertiesSet() {
        Map<String, Object> role = jdbc.queryForMap(POOL_ROLE);
        check((Boolean) role.get("privileged"), ((Number) role.get("owned_tables")).longValue());
    }

    static void check(boolean privileged, long ownedTables) {
        if (privileged) {
            throw new IllegalStateException("""
                    The pool's role (DB_USER) is a superuser or has BYPASSRLS: tenancy needs a plain role \\
                    (docs/tenancy-runbook.md, "Database roles")""");
        }
        if (ownedTables > 0) {
            throw new IllegalStateException("""
                    The pool's role (DB_USER) owns %d table(s): migrations must run as the owner role \\
                    (DB_OWNER_USER), and the pool's role must own nothing""".formatted(ownedTables));
        }
    }
}
