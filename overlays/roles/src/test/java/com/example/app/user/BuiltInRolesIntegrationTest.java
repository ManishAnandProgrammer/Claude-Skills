package com.example.app.user;

import com.example.app.common.security.Permission;
import com.example.app.support.IntegrationTest;
import com.example.app.user.domain.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The built-in roles as changeset 0037 (and every later grant changeset) seeded them, against the {@link Role} enum
 * (house-review AUTHZ-12): a new permission listed on a constant without the changeset that grants it to that role's
 * copy fails here, so an existing installation can never miss it after an upgrade. The tests that edit a built-in role
 * put it back.
 */
class BuiltInRolesIntegrationTest extends IntegrationTest {

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
}
