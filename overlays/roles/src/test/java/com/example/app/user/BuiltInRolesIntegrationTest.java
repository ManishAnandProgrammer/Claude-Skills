package com.example.app.user;

import com.example.app.common.security.Permission;
import com.example.app.support.IntegrationTest;
// tenancy-schema:begin
import com.example.app.tenant.service.TenantMigrations;
// tenancy-schema:end
import com.example.app.user.domain.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
// tenancy-schema:begin
import org.springframework.beans.factory.annotation.Autowired;
// tenancy-schema:end

import java.util.List;
// tenancy:begin
import java.util.Map;
import java.util.UUID;
// tenancy:end

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The built-in roles as changeset 0037 (and every later grant changeset) seeded them, against the {@link Role} enum
 * (house-review AUTHZ-12): a new permission listed on a constant without the changeset that grants it to that role's
 * copy fails here, so an existing installation can never miss it after an upgrade. The tests that edit a built-in role
 * put it back.
 *   <!-- tenancy:begin -->
 * With tenancy every tenant has its copies: those its changesets seeded (an upgraded tenant's; schema per tenant also
 * a new tenant's, from the tenant changelog) and, in row mode, a new tenant's, copied from the enum at its activation.
 * So in row mode the changesets are only compared on a database that held tenants before them: the upgrade run
 * ({@code app.test.upgrade.previous-release}, verify.sh's stages with the example), where the seeded tenants are.
 *   <!-- tenancy:end -->
 */
class BuiltInRolesIntegrationTest extends IntegrationTest {
    // tenancy-schema:begin

    @Autowired
    private TenantMigrations tenantMigrations;
    // tenancy-schema:end

    @ParameterizedTest(name = "{0}")
    @EnumSource(Role.class)
    @DisplayName("Each built-in role's copy holds exactly its Role constant's permissions")
    void eachBuiltInRoleHoldsItsConstantsPermissions(Role role) {
        List<String> seeded = jdbc.queryForList("""
                select p.permission
                  from role_permissions p
                  join roles r on r.id = p.role_id
                 where r.built_in = ?""", String.class, role.name());

        assertThat(seeded).as("the %s role's rows", role)
                .containsExactlyInAnyOrderElementsOf(role.permissions().stream().map(Permission::name).toList());
    }
    // tenancy:begin

    @ParameterizedTest(name = "{0}")
    @EnumSource(Role.class)
    @DisplayName("Every tenant's copy holds exactly its Role constant's permissions: those its changesets seeded and a new tenant's alike")
    void everyTenantsCopyHoldsItsConstantsPermissions(Role role) throws Exception {
        TestTenant fresh = newTenant();
        // tenancy-schema:begin
        // Every schema as a deploy leaves it: an upgraded one's tables come with the tenant migrations.
        assertThat(tenantMigrations.run().failed()).isEmpty();
        // tenancy-schema:end
        List<UUID> tenants = jdbc.queryForList("select id from tenants where status in ('ACTIVE', 'SUSPENDED', 'CLOSED')", UUID.class);
        assertThat(tenants).contains(fresh.id());

        for (UUID tenantId : tenants) {
            List<String> seeded = queryIn(tenant(tenantId), """
                    select p.permission
                      from role_permissions p
                      join roles r on r.id = p.role_id
                     where r.built_in = ?""", role.name()).stream().map(BuiltInRolesIntegrationTest::permissionOf).toList();
            assertThat(seeded).as("tenant %s's %s role", tenantId, role)
                    .containsExactlyInAnyOrderElementsOf(role.permissions().stream().map(Permission::name).toList());
        }
    }

    private static String permissionOf(Map<String, Object> row) {
        return row.get("permission").toString();
    }
    // tenancy:end
}
