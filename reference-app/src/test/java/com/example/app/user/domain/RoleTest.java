package com.example.app.user.domain;

import com.example.app.common.security.Permission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoleTest {

    @Test
    @DisplayName("Every role holds the prerequisites of each permission it holds: a dependency is checked, never granted for it")
    void everyRoleHoldsItsPermissionsPrerequisites() {
        for (Role role : Role.values()) {
            for (Permission permission : role.permissions()) {
                assertThat(role.permissions()).as("%s holds %s", role, permission).containsAll(permission.prerequisites());
            }
        }
    }

    @Test
    @DisplayName("A role grants exactly the permissions it lists")
    void aRoleGrantsWhatItLists() {
        for (Role role : Role.values()) {
            for (Permission permission : Permission.values()) {
                assertThat(role.grants(permission)).as("%s grants %s", role, permission).isEqualTo(role.permissions().contains(permission));
            }
        }
    }
}
