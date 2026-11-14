package com.example.app.tenant.row;

import com.example.app.common.tenancy.TenantContext;
import com.example.app.common.tenancy.TenantScope;
import com.example.app.support.IntegrationTest;
import com.example.app.support.WithoutTestTenant;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The row mode's two layers, as the application role sees them (experiments E1 and E2 of the design):
 * Hibernate's {@code @TenantId} filters and fills entity work, and row-level security, fed by the
 * transaction-local hook, refuses everything else that crosses tenants. Nothing of a tenant survives
 * its transaction.
 */
@WithoutTestTenant
class RowIsolationIntegrationTest extends IntegrationTest {

    private static final String COUNT_PROBES = "select count(*) from tenancy_probe";
    /** Two tenants with 2 and 3 probe rows, shared by the tests of this class. */
    private static TestTenant first;
    private static TestTenant second;
    private static UUID secondsProbe;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    /** The application's own JdbcTemplate, unlike the base's fixture one: it sees only what the hook set. */
    @Autowired
    private JdbcTemplate appJdbc;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void twoTenantsWithRows() throws Exception {
        if (Objects.isNull(first)) {
            first = newTenant();
            second = newTenant();
            insertProbes(first, 2);
            secondsProbe = insertProbes(second, 3);
        }
    }

    /** Written through the application's own path, as entities in the tenant's scope; the last one's id. */
    private UUID insertProbes(TestTenant tenant, int count) {
        return inTransactionOf(tenant.id(), () -> {
            TenancyProbe last = null;
            for (int index = 0; index < count; index++) {
                last = TenancyProbe.builder().label("probe " + index).build();
                entityManager.persist(last);
            }
            entityManager.flush();
            return last.getId();
        });
    }

    private <T> T inTransactionOf(UUID tenantId, Supplier<T> work) {
        return TenantScope.callAs(tenantId, () -> new TransactionTemplate(transactionManager).execute(status -> work.get()));
    }

    private long countIn(TestTenant tenant, boolean readOnly) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(readOnly);
        return TenantScope.callAs(tenant.id(), () -> transaction.execute(status -> appJdbc.queryForObject(COUNT_PROBES, Long.class)));
    }

    /** Every connection the pool holds, borrowed at once, as a plain JDBC user (no Hibernate, no hook) would. */
    private List<String> tenantOfEveryPooledConnection() throws SQLException {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        List<Connection> borrowed = new ArrayList<>();
        List<String> tenants = new ArrayList<>();
        try {
            for (int index = 0; index < pool.getMaximumPoolSize(); index++) {
                Connection connection = pool.getConnection();
                borrowed.add(connection);
                try (Statement statement = connection.createStatement();
                     ResultSet result = statement.executeQuery("select coalesce(current_setting('app.tenant_id', true), '')")) {
                    result.next();
                    tenants.add(result.getString(1));
                }
            }
        } finally {
            for (Connection connection : borrowed) {
                connection.rollback();
                connection.close();
            }
        }
        return tenants;
    }

    @Test
    @DisplayName("A tenant's transaction sees its own rows only, read-only or not, through plain SQL too")
    void eachTenantSeesItsOwnRows() {
        assertThat(countIn(first, true)).isEqualTo(2);
        assertThat(countIn(second, true)).isEqualTo(3);
        assertThat(countIn(first, false)).isEqualTo(2);
    }

    @Test
    @DisplayName("After a commit and after a rollback, no pooled connection keeps a tenant, and a plain borrower sees no rows")
    void nothingLeaksToTheNextBorrower() throws Exception {
        countIn(first, false);
        assertThat(tenantOfEveryPooledConnection()).containsOnly("");

        assertThatThrownBy(() -> TenantScope.runAs(second.id(), () -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    appJdbc.queryForObject(COUNT_PROBES, Long.class);
                    throw new IllegalStateException("roll back");
                }))).hasMessage("roll back");
        assertThat(tenantOfEveryPooledConnection()).containsOnly("");
        assertThat(appJdbc.queryForObject(COUNT_PROBES, Long.class)).isZero();
    }

    @Test
    @DisplayName("REQUIRES_NEW under another tenant gets that tenant; the outer transaction keeps its own")
    void requiresNewUnderAnotherTenant() {
        TransactionTemplate inner = new TransactionTemplate(transactionManager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        List<Long> counts = inTransactionOf(first.id(), () -> {
            long before = appJdbc.queryForObject(COUNT_PROBES, Long.class);
            long other = TenantScope.callAs(second.id(), () -> inner.execute(innerStatus -> appJdbc.queryForObject(COUNT_PROBES, Long.class)));
            long after = appJdbc.queryForObject(COUNT_PROBES, Long.class);
            return List.of(before, other, after);
        });

        assertThat(counts).containsExactly(2L, 3L, 2L);
    }

    @Test
    @DisplayName("A tenant switched inside an open transaction changes nothing: the tenant is fixed when it begins")
    void switchInsideATransactionIsIgnored() {
        long count = inTransactionOf(first.id(), () -> TenantScope.callAs(second.id(), () -> appJdbc.queryForObject(COUNT_PROBES, Long.class)));

        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("Without a tenant, platform tables work, tenant rows read as none and a tenant write is refused")
    void noTenantReachesThePlatformOnly() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        Long tenants = transaction.execute(status -> appJdbc.queryForObject("select count(*) from tenants", Long.class));
        Long probes = transaction.execute(status -> appJdbc.queryForObject(COUNT_PROBES, Long.class));

        assertThat(tenants).isPositive();
        assertThat(probes).isZero();
        // The column's default is NULL without a tenant: the policy's check refuses it before NOT NULL would.
        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                appJdbc.update("insert into tenancy_probe (id, label, created_at, updated_at, version) values (?, 'none', now(), now(), 0)", UUID.randomUUID())))
                .rootCause().hasMessageContaining("new row violates row-level security policy");
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> insertAs(first.id(), UUID.randomUUID())))
                .rootCause().hasMessageContaining("row-level security policy");
    }

    @Test
    @DisplayName("A row written for another tenant is refused by the policy, a child of another tenant's parent by the composite key")
    void writesNeverCrossTenants() {
        assertThatThrownBy(() -> inTransactionOf(first.id(), () -> insertAs(second.id(), UUID.randomUUID())))
                .rootCause().hasMessageContaining("new row violates row-level security policy");
        assertThatThrownBy(() -> inTransactionOf(first.id(), () -> appJdbc.update(
                "insert into tenancy_probe_notes (id, probe_id) values (?, ?)", UUID.randomUUID(), secondsProbe)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("fk_tenancy_probe_notes_probe_id");
        assertThat(countIn(second, true)).isEqualTo(3);
    }

    private int insertAs(UUID tenantId, UUID id) {
        return appJdbc.update("""
                insert into tenancy_probe (id, tenant_id, label, created_at, updated_at, version)
                values (?, ?, 'foreign', now(), now(), 0)""", id, tenantId);
    }

    @Test
    @DisplayName("@TenantId: another tenant's entity isn't found, queries and bulk updates stay in the tenant, inserts are filled")
    void hibernateFiltersAndFills() {
        Map<String, Object> seen = inTransactionOf(first.id(), () -> {
            TenancyProbe created = TenancyProbe.builder().label("filled").build();
            entityManager.persist(created);
            entityManager.flush();
            long counted = entityManager.createQuery("select count(probe) from TenancyProbe probe", Long.class).getSingleResult();
            int updated = entityManager.createQuery("update TenancyProbe probe set probe.label = 'bulk'").executeUpdate();
            entityManager.remove(created);
            return Map.of("foreign", Objects.isNull(entityManager.find(TenancyProbe.class, secondsProbe)),
                    "tenant", created.getTenantId(), "counted", counted, "updated", updated);
        });

        assertThat(seen).containsEntry("foreign", true).containsEntry("tenant", first.id())
                .containsEntry("counted", 3L).containsEntry("updated", 3);
    }

    @Test
    @DisplayName("@TenantId refuses an entity that names another tenant before any SQL runs")
    void hibernateRefusesAForeignTenantId() {
        assertThatThrownBy(() -> inTransactionOf(first.id(), () -> {
            entityManager.persist(TenancyProbe.builder().label("foreign").tenantId(second.id()).build());
            entityManager.flush();
            return null;
        })).hasMessageContaining("tenant");
        assertThat(countIn(second, true)).isEqualTo(3);
    }

    @Test
    @DisplayName("Native SQL, which Hibernate doesn't filter, is filtered by the database")
    void nativeQueriesAreFilteredByTheDatabase() {
        Number counted = inTransactionOf(first.id(), () ->
                (Number) entityManager.createNativeQuery(COUNT_PROBES).getSingleResult());

        assertThat(counted.longValue()).isEqualTo(2);
    }

    @Test
    @DisplayName("The policy's own predicate is an index condition: a native query needs no tenant parameter to use the tenant's index")
    void thePolicyPredicateUsesTheTenantIndex() {
        String plan = inTransactionOf(first.id(), () -> {
            // Few rows: without this the planner rightly reads the table; the test is whether the index can serve.
            appJdbc.execute("set local enable_seqscan = off");
            return String.join("\n", appJdbc.queryForList("explain select id from tenancy_probe where label = 'probe 1'", String.class));
        });

        assertThat(plan).contains("idx_tenancy_probe_tenant_id_label_id").containsPattern("Index Cond: .*tenant_id = .*current_setting");
    }

    @Test
    @DisplayName("Operators that aren't leakproof (jsonb @>, LIKE, ILIKE, enum =, range &&) can't run before the policy: house-review TEN-36")
    void leakproofOperators() {
        List<Map<String, Object>> operators = appJdbc.queryForList("""
                select proname, proleakproof from pg_proc
                 where proname in ('uuid_eq', 'texteq', 'int8eq', 'timestamptz_lt', 'jsonb_contains', 'textlike', 'texticlike', 'enum_eq', 'range_overlaps')""");

        assertThat(operators).extracting(row -> row.get("proname") + "=" + row.get("proleakproof")).containsExactlyInAnyOrder(
                "uuid_eq=true", "texteq=true", "int8eq=true", "timestamptz_lt=true",
                "jsonb_contains=false", "textlike=false", "texticlike=false", "enum_eq=false", "range_overlaps=false");
    }

    @Test
    @DisplayName("A read-only transaction is read-only in the database and refuses a write; one that runs no SQL borrows no connection")
    void readOnlyAndLazyBorrowing() throws Exception {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);

        Integer activeWithoutSql = TenantScope.callAs(first.id(), () -> readOnly.execute(status ->
                pool.getHikariPoolMXBean().getActiveConnections()));
        String flag = TenantScope.callAs(first.id(), () -> readOnly.execute(status ->
                appJdbc.queryForObject("show transaction_read_only", String.class)));

        assertThat(activeWithoutSql).isZero();
        assertThat(flag).isEqualTo("on");
        assertThatThrownBy(() -> TenantScope.runAs(first.id(), () -> readOnly.executeWithoutResult(status ->
                appJdbc.update("insert into tenancy_probe (id, label, created_at, updated_at, version) values (?, 'read-only', now(), now(), 0)",
                        UUID.randomUUID()))))
                .hasMessageContaining("read-only transaction");
        assertThat(countIn(first, true)).isEqualTo(2);
    }

    @Test
    @DisplayName("The hook names the scope's tenant and only that one: the session resolves it, the database is told it")
    void theDatabaseIsToldTheScopesTenant() {
        String told = inTransactionOf(first.id(), () -> appJdbc.queryForObject("select current_setting('app.tenant_id')", String.class));
        String none = new TransactionTemplate(transactionManager).execute(status ->
                appJdbc.queryForObject("select current_setting('app.tenant_id')", String.class));

        assertThat(told).isEqualTo(first.id().toString());
        assertThat(none).isEmpty();
        assertThat(TenantContext.current()).isEqualTo(TenantContext.NO_TENANT);
    }
}
