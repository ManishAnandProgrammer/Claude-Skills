package com.example.app.user;

import com.example.app.common.dropdown.DropdownFilter;
import com.example.app.common.security.Permission;
import com.example.app.support.RolesFixtures;
import com.example.app.user.api.PermissionHolders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PermissionHoldersAccessIntegrationTest extends RolesFixtures {

    @Autowired
    private PermissionHolders holders;

    private TestUser named(TestUser user, String fullName) {
        assertThat(sql("update users set full_name = ? where id = ?", fullName, user.id())).isEqualTo(1);
        return user;
    }

    private List<UUID> holdersOf(Permission permission, String query) {
        DropdownFilter filter = new DropdownFilter();
        filter.setQuery(query);
        return holders.findHolders(permission, filter, 10).items().stream().map(item -> (UUID) item.value()).toList();
    }

    private void grant(String admin, TestUser person, Permission permission, String effect) throws Exception {
        putAs(admin, userPermission(person.id(), permission), """
                {"effect": "%s"}""".formatted(effect)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("""
            The holders follow the effective access: a role or an ALLOW gives, a DENY takes away, a permission without \
            its prerequisite is not held, an expired role gives nothing; holds and the picker agree on each""")
    void theHoldersAreTheEffectiveAccess() throws Exception {
        String admin = newAdmin().accessToken();
        String prefix = "Access-" + UUID.randomUUID();
        String both = createRole(admin, Permission.ACCOUNT_READ, Permission.ACCOUNT_STATUS_CHANGE);
        TestUser byRole = named(newUser(), prefix + " Ann");
        assign(admin, byRole.id(), both, LATER).andExpect(status().isOk());
        TestUser byAllow = named(newUser(), prefix + " Bob");
        grant(admin, byAllow, Permission.ACCOUNT_READ, "ALLOW");
        TestUser denied = named(newUser(), prefix + " Cid");
        assign(admin, denied.id(), both, LATER).andExpect(status().isOk());
        grant(admin, denied, Permission.ACCOUNT_READ, "DENY");
        TestUser withoutPrerequisite = named(newUser(), prefix + " Dee");
        grant(admin, withoutPrerequisite, Permission.ACCOUNT_STATUS_CHANGE, "ALLOW");
        TestUser expired = named(newUser(), prefix + " Eve");
        assign(admin, expired.id(), both, LATER).andExpect(status().isOk());
        assertThat(sql("update user_roles set valid_until = now() - interval '1 minute' where user_id = ? and role_id = ?",
                expired.id(), UUID.fromString(both))).isEqualTo(1);

        assertThat(holdersOf(Permission.ACCOUNT_READ, prefix)).containsExactly(byRole.id(), byAllow.id());
        assertThat(holdersOf(Permission.ACCOUNT_STATUS_CHANGE, prefix)).containsExactly(byRole.id());
        for (TestUser person : List.of(byRole, byAllow, denied, withoutPrerequisite, expired)) {
            for (Permission permission : List.of(Permission.ACCOUNT_READ, Permission.ACCOUNT_STATUS_CHANGE)) {
                assertThat(holders.holds(person.id(), permission)).as("%s holds %s", person.id(), permission)
                        .isEqualTo(holdersOf(permission, prefix).contains(person.id()));
            }
        }
    }
}
