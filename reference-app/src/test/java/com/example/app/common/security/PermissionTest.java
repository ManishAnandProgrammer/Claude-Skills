package com.example.app.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionTest {

    @Test
    @DisplayName("A permission's reach and its name's suffix agree: _OWN is OWN, _ASSIGNED is ASSIGNED, no suffix is ALL")
    void theReachMatchesTheName() {
        for (Permission permission : Permission.values()) {
            Reach named = permission.name().endsWith("_OWN") ? Reach.OWN
                    : permission.name().endsWith("_ASSIGNED") ? Reach.ASSIGNED : Reach.ALL;
            assertThat(permission.reach()).as(permission.name()).isEqualTo(named);
        }
    }

    @Test
    @DisplayName("Every area holds at least one permission: an area no one can ever be shown is a menu item without a screen")
    void everyAreaHoldsAPermission() {
        Set<Area> used = EnumSet.noneOf(Area.class);
        for (Permission permission : Permission.values()) {
            used.add(permission.area());
        }
        assertThat(used).containsExactlyInAnyOrder(Area.values());
    }

    @Test
    @DisplayName("No permission needs itself, and a prerequisite needs nothing in turn: one level, so a role's set reads at a glance")
    void prerequisitesAreOneLevelDeep() {
        for (Permission permission : Permission.values()) {
            assertThat(permission.prerequisites()).as(permission.name()).doesNotContain(permission);
            for (Permission prerequisite : permission.prerequisites()) {
                assertThat(prerequisite.prerequisites()).as(permission + " needs " + prerequisite).isEmpty();
            }
        }
    }
}
