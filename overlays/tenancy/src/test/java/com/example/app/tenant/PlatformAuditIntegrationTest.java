package com.example.app.tenant;

import com.example.app.support.IntegrationTest;
import com.example.app.support.ReadCost;
import com.example.app.support.WithoutTestTenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Staff read a tenant's support history: every impersonation and direct edit, newest first, in batches. */
@WithoutTestTenant
class PlatformAuditIntegrationTest extends IntegrationTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static String auditOf(UUID tenantId) {
        return "/api/v1/platform/tenants/" + tenantId + "/audit";
    }

    private void impersonate(UUID tenantId, String reason) throws Exception {
        postAs(platformToken(), "/api/v1/platform/tenants/" + tenantId + "/impersonations", """
                {"reason": "%s"}
                """.formatted(reason)).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Staff read a tenant's entries newest first; the cursor walks every batch to the last, and no other tenant's entry shows")
    void staffReadTheHistoryInBatches() throws Exception {
        TestTenant tenant = newTenant();
        TestTenant other = newTenant();
        for (String reason : List.of("SUP-1", "SUP-2", "SUP-3")) {
            impersonate(tenant.id(), reason);
        }
        impersonate(other.id(), "SUP-OTHER");

        String next = read(mvc.perform(get(auditOf(tenant.id())).param("size", "2")
                        .header("Authorization", "Bearer " + platformToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", hasSize(2)))
                .andExpect(jsonPath("$.response[0].action").value("IMPERSONATION"))
                .andExpect(jsonPath("$.response[0].detail").value(endsWith("reason=SUP-3")))
                .andExpect(jsonPath("$.response[1].detail").value(endsWith("reason=SUP-2")))
                .andExpect(jsonPath("$.pageInfo.hasMore").value(true)), "$.pageInfo.nextCursor");

        mvc.perform(get(auditOf(tenant.id())).param("size", "2").param("after", next)
                        .header("Authorization", "Bearer " + platformToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].detail", contains(endsWith("reason=SUP-1"))))
                .andExpect(jsonPath("$.pageInfo.hasMore").value(false));
    }

    @Test
    @DisplayName("An unknown tenant is 404 TENANT_NOT_FOUND, a tampered cursor 400, a tenant token 401")
    void refusals() throws Exception {
        TestTenant tenant = newTenant();

        getAs(platformToken(), auditOf(UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TENANT_NOT_FOUND"));
        mvc.perform(get(auditOf(tenant.id())).param("after", "not-a-cursor")
                        .header("Authorization", "Bearer " + platformToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"));
        getAs(newUserIn(tenant).accessToken(), auditOf(tenant.id()))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The audit list's two batch shapes (crud-gen tests.md row 28): the index exists and a batch reads at most
     * two pages, for a tenant's 500 entries beside another tenant's 10,000, interleaved in id order.
     */
    @Test
    @DisplayName("Both batch shapes have idx_platform_audit_tenant_id_id and read at most two pages")
    void batchesReadAtMostTwoPages() throws Exception {
        UUID tenant = newTenant().id();
        UUID other = newTenant().id();
        // The staff account the platform token signs in, an author every audit row needs.
        platformToken();
        UUID actor = jdbc.queryForObject("select id from platform_users order by id limit 1", UUID.class);
        for (UUID seeded : List.of(tenant, other)) {
            sql("""
                    insert into platform_audit (id, actor_id, tenant_id, action, created_at, updated_at, version)
                    select gen_random_uuid(), ?, ?, 'SEEDED', now(), now(), 0 from generate_series(1, ?)
                    """, actor, seeded, seeded.equals(tenant) ? 500 : 10_000);
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> jdbc.execute("analyze platform_audit"));
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenant", tenant)
                .addValue("after", jdbc.queryForObject(
                        "select id from platform_audit where tenant_id = ? order by id desc offset 250 limit 1", UUID.class, tenant));

        for (String statement : List.of(
                "select id from platform_audit where tenant_id = :tenant order by id desc limit 21",
                "select id from platform_audit where tenant_id = :tenant and id < :after order by id desc limit 21")) {
            ReadCost cost = ReadCost.of(jdbc, transactionManager, statement, parameters);

            assertThat(cost.rowsRead()).as(cost.plan()).isLessThanOrEqualTo(42);
        }
        assertThat(ReadCost.indexExists(jdbc, "idx_platform_audit_tenant_id_id")).isTrue();
    }
}
