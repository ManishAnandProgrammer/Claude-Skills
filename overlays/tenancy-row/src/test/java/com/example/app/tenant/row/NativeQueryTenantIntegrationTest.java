package com.example.app.tenant.row;

import com.example.app.support.IntegrationTest;
import com.example.app.support.WithoutTestTenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.support.Repositories;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every native statement on a tenant table names its tenant (house-review TEN-37): Hibernate's tenant
 * filter never reaches native SQL, and the policy is the fallback, not the filter. Read from the
 * repositories' {@code @Query(nativeQuery = true)} statements: each reference to a tenant table (after
 * {@code FROM}, {@code JOIN}, {@code UPDATE} or {@code INTO}) needs its own {@code tenant_id = :tenantId}.
 * Native SQL lives in repositories (a {@code JdbcTemplate} on a tenant table elsewhere is a review
 * finding this test can't see).
 */
@WithoutTestTenant
class NativeQueryTenantIntegrationTest extends IntegrationTest {

    /** The tables a statement reads or writes, by the keyword before them. */
    private static final Pattern TABLE = Pattern.compile("\\b(?:from|join|update|into)\\s+(\\w+)", Pattern.CASE_INSENSITIVE);
    /** The tenant predicate, with or without a table alias. */
    private static final Pattern TENANT_PREDICATE = Pattern.compile("\\btenant_id\\s*=\\s*:tenantId\\b");

    @Autowired
    private ApplicationContext context;

    /** The tables under the tenant policy, from the catalogue. */
    private Set<String> tenantTables() {
        return Set.copyOf(jdbc.queryForList("""
                select c.relname from pg_policy p join pg_class c on c.oid = p.polrelid
                 where p.polname = 'tenant_isolation'""", String.class));
    }

    /** True when the statement names fewer tenant predicates than it has references to tenant tables. */
    private static boolean missesItsTenant(String statement, Set<String> tenantTables) {
        int tenantTableReferences = 0;
        Matcher table = TABLE.matcher(statement);
        while (table.find()) {
            if (tenantTables.contains(table.group(1).toLowerCase(Locale.ROOT))) {
                tenantTableReferences++;
            }
        }
        long predicates = TENANT_PREDICATE.matcher(statement).results().count();
        return predicates < tenantTableReferences;
    }

    @Test
    @DisplayName("Every native repository statement on a tenant table carries tenant_id = :tenantId for each tenant table it names")
    void everyNativeStatementNamesItsTenant() {
        Set<String> tenantTables = tenantTables();
        Repositories repositories = new Repositories(context);
        List<String> missing = new ArrayList<>();
        int nativeStatements = 0;
        for (Class<?> domainType : repositories) {
            RepositoryInformation repository = repositories.getRequiredRepositoryInformation(domainType);
            for (Method method : repository.getRepositoryInterface().getMethods()) {
                Query query = method.getAnnotation(Query.class);
                if (Objects.nonNull(query) && query.nativeQuery()) {
                    nativeStatements++;
                    if (missesItsTenant(query.value(), tenantTables)) {
                        missing.add(repository.getRepositoryInterface().getSimpleName() + "." + method.getName());
                    }
                }
            }
        }

        assertThat(nativeStatements).as("native statements found (the scan reads the repositories)").isPositive();
        assertThat(missing).isEmpty();
    }

    @Test
    @DisplayName("The check catches a statement without the predicate, and a subquery's table left without its own")
    void theCheckCatchesAMissingPredicate() {
        Set<String> tenantTables = Set.of("tenancy_probe");

        assertThat(missesItsTenant("UPDATE tenancy_probe SET label = 'renamed' WHERE id IN (:ids)", tenantTables)).isTrue();
        assertThat(missesItsTenant("""
                UPDATE tenancy_probe SET label = 'renamed'
                WHERE id IN (SELECT id FROM tenancy_probe WHERE label = 'probe')
                  AND tenant_id = :tenantId""", tenantTables)).isTrue();
        assertThat(missesItsTenant("""
                UPDATE tenancy_probe SET label = 'renamed'
                WHERE id IN (SELECT id FROM tenancy_probe WHERE label = 'probe' AND tenant_id = :tenantId)
                  AND tenant_id = :tenantId""", tenantTables)).isFalse();
        assertThat(missesItsTenant("DELETE FROM refresh_tokens WHERE expires_at < :cutoff", tenantTables)).isFalse();
    }
}
