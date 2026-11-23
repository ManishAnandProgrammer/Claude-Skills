package com.example.app.tenant.row;

import com.example.app.common.persistence.TenantOwnedEntity;
import com.example.app.support.IntegrationTest;
import com.example.app.support.WithoutTestTenant;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.EntityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WithoutTestTenant
class RowSecurityCatalogIntegrationTest extends IntegrationTest {

    private static final Map<String, String> PLATFORM_TABLES = Map.of(
            "users", "global identities: one account, memberships in several tenants",
            "refresh_tokens", "read at refresh, before any tenant is known (the token names its tenant)",
            "tenants", "the tenant register itself",
            "tenant_status_changes", "the register's history, read by staff",
            "tenant_memberships", "login and 'my tenants' read a user's memberships across tenants",
            "platform_users", "platform staff, who belong to no tenant",
            "platform_audit", "staff actions, written on the platform plane",
            // change-history:begin
            "audit_log", "the change history: every tenant's changes in one table, each with its tenant_id; compliance only",
            // change-history:end
            "databasechangelog", "Liquibase's own",
            "databasechangeloglock", "Liquibase's own");

    /** The tenant policy as {@code pg_get_expr} prints it back. */
    private static final String POLICY_EXPRESSION = "(tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)";

    /** A second permissive policy would widen the first, so the count matters. */
    private static final String TENANT_TABLES_WITHOUT_THEIR_POLICY = """
            select c.relname
              from pg_class c
              join pg_namespace n on n.oid = c.relnamespace
             where n.nspname = current_schema()
               and c.relkind in ('r', 'p')
               and exists (select 1 from pg_attribute a
                            where a.attrelid = c.oid and a.attname = 'tenant_id' and not a.attisdropped)
               and not (c.relname = any (?))
               and not (c.relrowsecurity
                        and c.relforcerowsecurity
                        and (select count(*) from pg_policy p where p.polrelid = c.oid) = 1
                        and exists (select 1 from pg_policy p
                                     where p.polrelid = c.oid
                                       and p.polcmd = '*'
                                       and p.polpermissive
                                       and p.polroles = '{0}'
                                       and pg_get_expr(p.polqual, p.polrelid) = ?
                                       and pg_get_expr(p.polwithcheck, p.polrelid) = ?))
             order by c.relname""";

    private static final String TABLES_NEITHER_TENANT_NOR_PLATFORM = """
            select c.relname
              from pg_class c
              join pg_namespace n on n.oid = c.relnamespace
             where n.nspname = current_schema()
               and c.relkind in ('r', 'p')
               and not exists (select 1 from pg_attribute a
                                where a.attrelid = c.oid and a.attname = 'tenant_id' and not a.attisdropped)
               and not (c.relname = any (?))
             order by c.relname""";

    /** No default: an insert that forgets its tenant fails at once instead of taking whatever the session was told. */
    private static final String TENANT_IDS_UNCHECKED = """
            select c.relname
              from pg_class c
              join pg_namespace n on n.oid = c.relnamespace
              join pg_attribute a on a.attrelid = c.oid and a.attname = 'tenant_id' and not a.attisdropped
             where n.nspname = current_schema()
               and c.relkind in ('r', 'p')
               and not (c.relname = any (?))
               and not (a.attnotnull
                        and not a.atthasdef
                        and exists (select 1 from pg_constraint f
                                     where f.conrelid = c.oid and f.contype = 'f'
                                       and f.conkey = array[a.attnum] and f.confrelid = 'tenants'::regclass))
             order by c.relname""";

    /**
     * A key that doesn't pair tenant_id with tenant_id lets a row point at another tenant's parent, which no policy
     * can see: key checks bypass row security.
     */
    private static final String FOREIGN_KEYS_ACROSS_TENANTS = """
            select f.conname
              from pg_constraint f
              join pg_class child on child.oid = f.conrelid
              join pg_class parent on parent.oid = f.confrelid
              join pg_namespace n on n.oid = child.relnamespace
              join pg_attribute child_tenant on child_tenant.attrelid = child.oid and child_tenant.attname = 'tenant_id'
              join pg_attribute parent_tenant on parent_tenant.attrelid = parent.oid and parent_tenant.attname = 'tenant_id'
             where n.nspname = current_schema()
               and f.contype = 'f'
               and not (child.relname = any (?))
               and not (parent.relname = any (?))
               and not exists (select 1 from unnest(f.conkey, f.confkey) as pair(child_column, parent_column)
                                where pair.child_column = child_tenant.attnum and pair.parent_column = parent_tenant.attnum)
             order by f.conname""";

    /**
     * One tenant's rows leave through a publication filtered by tenant_id, and PostgreSQL refuses UPDATE and DELETE
     * on a table whose row filter names a column outside the replica identity.
     */
    private static final String TENANT_TABLES_WITHOUT_A_TENANT_REPLICA_IDENTITY = """
            select c.relname
              from pg_class c
              join pg_namespace n on n.oid = c.relnamespace
              join pg_attribute a on a.attrelid = c.oid and a.attname = 'tenant_id' and not a.attisdropped
             where n.nspname = current_schema()
               and c.relkind in ('r', 'p')
               and not (c.relname = any (?))
               and not (c.relreplident = 'i'
                        and exists (select 1 from pg_index i
                                     where i.indrelid = c.oid and i.indisreplident
                                       and a.attnum = any (i.indkey::int2[])))
             order by c.relname""";

    /** A tenant's rows must form a closed graph, to move to a database of their own. */
    private static final String FOREIGN_KEYS_INTO_THE_PLATFORM = """
            select f.conname
              from pg_constraint f
              join pg_class child on child.oid = f.conrelid
              join pg_class parent on parent.oid = f.confrelid
              join pg_namespace n on n.oid = child.relnamespace
             where n.nspname = current_schema()
               and f.contype = 'f'
               and exists (select 1 from pg_attribute a
                            where a.attrelid = child.oid and a.attname = 'tenant_id' and not a.attisdropped)
               and not (child.relname = any (?))
               and parent.relname = any (?)
               and parent.relname <> 'tenants'
             order by f.conname""";

    /**
     * One sequence serves every tenant, so numbers drawn from it for tenant data interleave across tenants and leak
     * their volume.
     */
    private static final Map<String, String> PLATFORM_SEQUENCES = Map.of();

    private static final String SEQUENCES_NOT_NAMED = """
            select c.relname
              from pg_class c
              join pg_namespace n on n.oid = c.relnamespace
             where n.nspname = current_schema()
               and c.relkind = 'S'
               and not (c.relname = any (?))
             order by c.relname""";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Value("${app.liquibase.username}")
    private String ownerUser;

    @Value("${app.liquibase.password}")
    private String ownerPassword;

    private static String[] platformTables() {
        return PLATFORM_TABLES.keySet().toArray(String[]::new);
    }

    private static List<String> tenantTablesWithoutTheirPolicy(JdbcTemplate catalog) {
        return catalog.queryForList(TENANT_TABLES_WITHOUT_THEIR_POLICY, String.class, platformTables(), POLICY_EXPRESSION, POLICY_EXPRESSION);
    }

    @Test
    @DisplayName("Every tenant table has row-level security enabled and forced, with the one tenant policy")
    void everyTenantTableHasItsPolicy() {
        assertThat(tenantTablesWithoutTheirPolicy(jdbc)).isEmpty();
    }

    @Test
    @DisplayName("Every table is a tenant table or a platform table named with its reason")
    void everyTableIsTenantOrPlatform() {
        assertThat(jdbc.queryForList(TABLES_NEITHER_TENANT_NOR_PLATFORM, String.class, (Object) platformTables())).isEmpty();
    }

    private static List<String> tenantIdsUnchecked(JdbcTemplate catalog) {
        return catalog.queryForList(TENANT_IDS_UNCHECKED, String.class, (Object) platformTables());
    }

    @Test
    @DisplayName("Every tenant table's tenant_id is NOT NULL, references tenants and has no default")
    void everyTenantIdIsChecked() {
        assertThat(tenantIdsUnchecked(jdbc)).isEmpty();
    }

    @Test
    @DisplayName("Every foreign key between tenant tables carries tenant_id on both sides")
    void foreignKeysStayInTheirTenant() {
        assertThat(jdbc.queryForList(FOREIGN_KEYS_ACROSS_TENANTS, String.class, platformTables(), platformTables())).isEmpty();
    }

    private static List<String> tenantTablesWithoutATenantReplicaIdentity(JdbcTemplate catalog) {
        return catalog.queryForList(TENANT_TABLES_WITHOUT_A_TENANT_REPLICA_IDENTITY, String.class, (Object) platformTables());
    }

    private static List<String> foreignKeysIntoThePlatform(JdbcTemplate catalog) {
        return catalog.queryForList(FOREIGN_KEYS_INTO_THE_PLATFORM, String.class, platformTables(), platformTables());
    }

    @Test
    @DisplayName("Every tenant table's replica identity is a unique index that includes tenant_id")
    void everyReplicaIdentityCarriesTheTenant() {
        assertThat(tenantTablesWithoutATenantReplicaIdentity(jdbc)).isEmpty();
    }

    @Test
    @DisplayName("No tenant table references a platform table but tenants: people are the tenant's own members")
    void tenantDataReferencesNoPlatformTable() {
        assertThat(foreignKeysIntoThePlatform(jdbc)).isEmpty();
    }

    @Test
    @DisplayName("No sequence numbers tenant data: a tenant's display numbers are its own counters")
    void noSequenceIsSharedByTheTenants() {
        assertThat(jdbc.queryForList(SEQUENCES_NOT_NAMED, String.class, (Object) PLATFORM_SEQUENCES.keySet().toArray(String[]::new)))
                .isEmpty();
    }

    @Test
    @DisplayName("Every entity of a tenant table extends TenantOwnedEntity (Hibernate's @TenantId), and no platform entity does")
    void entitiesMatchTheirTables() {
        List<String> mismatched = new ArrayList<>();
        for (EntityType<?> entity : entityManagerFactory.getMetamodel().getEntities()) {
            Class<?> type = entity.getJavaType();
            String table = type.getAnnotation(Table.class).name();
            boolean tenantOwned = TenantOwnedEntity.class.isAssignableFrom(type);
            if (tenantOwned == PLATFORM_TABLES.containsKey(table)) {
                mismatched.add(type.getSimpleName() + " (" + table + ")");
            }
        }
        assertThat(mismatched).isEmpty();
    }

    @Test
    @DisplayName("The application role is no superuser, doesn't bypass row-level security and owns no table")
    void theApplicationRoleIsBound() {
        Map<String, Object> role = jdbc.queryForMap("select rolsuper, rolbypassrls from pg_roles where rolname = current_user");
        Long owned = jdbc.queryForObject("""
                select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace
                 where n.nspname = current_schema() and c.relowner = (select oid from pg_roles where rolname = current_user)""", Long.class);

        assertThat(role).containsEntry("rolsuper", false).containsEntry("rolbypassrls", false);
        assertThat(owned).isZero();
    }

    @Test
    @DisplayName("The guard catches a tenant table without its policy, one whose security is enabled but not forced, and a tenant_id with a default")
    void theGuardCatchesAMissingPolicy() throws SQLException {
        String jdbcUrl = dataSource.unwrap(HikariDataSource.class).getJdbcUrl();
        try (Connection owner = DriverManager.getConnection(jdbcUrl, ownerUser, ownerPassword)) {
            owner.setAutoCommit(false);
            JdbcTemplate asOwner = new JdbcTemplate(new SingleConnectionDataSource(owner, true));
            try {
                asOwner.execute("create table guard_probe (id uuid primary key, tenant_id uuid not null references tenants (id))");
                List<String> withoutPolicy = tenantTablesWithoutTheirPolicy(asOwner);
                asOwner.execute("alter table guard_probe enable row level security");
                asOwner.execute("""
                        create policy tenant_isolation on guard_probe
                            using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
                            with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)""");
                List<String> notForced = tenantTablesWithoutTheirPolicy(asOwner);
                asOwner.execute("alter table guard_probe force row level security");
                List<String> complete = tenantTablesWithoutTheirPolicy(asOwner);

                List<String> withoutDefault = tenantIdsUnchecked(asOwner);
                asOwner.execute("alter table guard_probe alter column tenant_id set default nullif(current_setting('app.tenant_id', true), '')::uuid");
                List<String> withDefault = tenantIdsUnchecked(asOwner);

                assertThat(withoutPolicy).contains("guard_probe");
                assertThat(notForced).contains("guard_probe");
                assertThat(complete).doesNotContain("guard_probe");
                assertThat(withoutDefault).doesNotContain("guard_probe");
                assertThat(withDefault).contains("guard_probe");
            } finally {
                owner.rollback();
            }
        }
    }

    @Test
    @DisplayName("The guards catch a tenant table without a replica identity holding tenant_id, and a key to users")
    void theExtractionGuardsCatchTheirCases() throws SQLException {
        try (Connection owner = ownerConnection()) {
            JdbcTemplate asOwner = new JdbcTemplate(new SingleConnectionDataSource(owner, true));
            try {
                asOwner.execute("""
                        create table guard_probe (id uuid primary key, tenant_id uuid not null references tenants (id),
                                                  owner_id uuid not null constraint fk_guard_probe_owner_id references users (id))""");
                List<String> withThePrimaryKey = tenantTablesWithoutATenantReplicaIdentity(asOwner);
                List<String> withAKeyToUsers = foreignKeysIntoThePlatform(asOwner);
                asOwner.execute("create unique index uq_guard_probe_id_tenant_id on guard_probe (id, tenant_id)");
                asOwner.execute("alter table guard_probe replica identity using index uq_guard_probe_id_tenant_id");
                asOwner.execute("alter table guard_probe drop constraint fk_guard_probe_owner_id");

                assertThat(withThePrimaryKey).contains("guard_probe");
                assertThat(withAKeyToUsers).contains("fk_guard_probe_owner_id");
                assertThat(tenantTablesWithoutATenantReplicaIdentity(asOwner)).doesNotContain("guard_probe");
                assertThat(foreignKeysIntoThePlatform(asOwner)).doesNotContain("fk_guard_probe_owner_id");
            } finally {
                owner.rollback();
            }
        }
    }

    /** Publications don't need wal_level=logical to exist, and the replica identity check runs anyway. */
    @Test
    @DisplayName("A publication filtered by tenant_id: UPDATE and DELETE pass with the tenant in the replica identity, and fail without it")
    void aTenantFilteredPublicationAcceptsUpdatesAndDeletes() throws SQLException {
        UUID tenantId = testTenant().id();
        try (Connection owner = ownerConnection()) {
            JdbcTemplate asOwner = new JdbcTemplate(new SingleConnectionDataSource(owner, true));
            try {
                // The owner is bound by the forced policy too.
                asOwner.queryForObject("select set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
                asOwner.execute("create publication extract_probe for table tenancy_probe where (tenant_id = '%s')".formatted(tenantId));
                UUID probe = UUID.randomUUID();
                asOwner.update("""
                        insert into tenancy_probe (id, tenant_id, label, created_at, updated_at, version)
                        values (?, ?, 'extracted', now(), now(), 0)""", probe, tenantId);

                assertThat(asOwner.update("update tenancy_probe set label = 'moved' where id = ?", probe)).isEqualTo(1);
                assertThat(asOwner.update("delete from tenancy_probe where id = ?", probe)).isEqualTo(1);

                asOwner.execute("create table guard_probe (id uuid primary key, tenant_id uuid not null)");
                asOwner.execute("alter publication extract_probe add table guard_probe where (tenant_id = '%s')".formatted(tenantId));
                asOwner.update("insert into guard_probe (id, tenant_id) values (?, ?)", probe, tenantId);
                asOwner.execute("savepoint before_update");
                assertThatThrownBy(() -> asOwner.update("update guard_probe set tenant_id = tenant_id where id = ?", probe))
                        .rootCause().hasMessageContaining("not part of the replica identity");
                asOwner.execute("rollback to savepoint before_update");
                assertThatThrownBy(() -> asOwner.update("delete from guard_probe where id = ?", probe))
                        .rootCause().hasMessageContaining("not part of the replica identity");
            } finally {
                owner.rollback();
            }
        }
    }

    private Connection ownerConnection() throws SQLException {
        String jdbcUrl = dataSource.unwrap(HikariDataSource.class).getJdbcUrl();
        Connection owner = DriverManager.getConnection(jdbcUrl, ownerUser, ownerPassword);
        owner.setAutoCommit(false);
        return owner;
    }

    @Test
    @DisplayName("The owner is bound by the forced policy too, until a changeset lifts it for its own transaction (runbook, section 4)")
    void theOwnerIsBoundUntilTheForceIsLifted() throws SQLException {
        UUID tenantId = testTenant().id();
        String jdbcUrl = dataSource.unwrap(HikariDataSource.class).getJdbcUrl();
        try (Connection owner = DriverManager.getConnection(jdbcUrl, ownerUser, ownerPassword)) {
            owner.setAutoCommit(false);
            JdbcTemplate asOwner = new JdbcTemplate(new SingleConnectionDataSource(owner, true));
            String insert = """
                    insert into tenancy_probe (id, tenant_id, label, created_at, updated_at, version)
                    values (gen_random_uuid(), ?, 'owner', now(), now(), 0)""";
            try {
                asOwner.execute("savepoint before_insert");
                assertThatThrownBy(() -> asOwner.update(insert, tenantId)).rootCause().hasMessageContaining("row-level security policy");
                asOwner.execute("rollback to savepoint before_insert");
                asOwner.execute("alter table tenancy_probe no force row level security");

                assertThat(asOwner.update(insert, tenantId)).isEqualTo(1);
            } finally {
                owner.rollback();
            }
        }
        assertThat(tenantTablesWithoutTheirPolicy(jdbc)).doesNotContain("tenancy_probe");
    }

    @Test
    @DisplayName("The platform list names only tables that exist")
    void thePlatformListIsCurrent() {
        List<String> existing = jdbc.queryForList("""
                select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
                 where n.nspname = current_schema() and c.relkind in ('r', 'p')""", String.class);

        assertThat(Set.copyOf(existing)).containsAll(PLATFORM_TABLES.keySet());
    }
}
