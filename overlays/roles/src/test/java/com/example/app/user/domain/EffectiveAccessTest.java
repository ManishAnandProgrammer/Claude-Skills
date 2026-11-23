package com.example.app.user.domain;

import com.example.app.common.security.Permission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class EffectiveAccessTest {

    // tenancy:begin
    private static final Permission NEEDED = Permission.TENANT_MEMBERS_MANAGE;
    private static final Permission NEEDING = Permission.ACCESS_GRANT;
    // tenancy:else
    // private static final Permission NEEDED = Permission.ACCOUNT_READ;
    // private static final Permission NEEDING = Permission.ACCOUNT_STATUS_CHANGE;
    // tenancy:end
    private static final UUID PERSON = UUID.randomUUID();
    private static final UUID ROLE = UUID.randomUUID();
    private static final Instant LATER = Instant.parse("2999-01-01T00:00:00Z");

    private static AccessRow fromRole(String roleCode, Permission permission) {
        return new AccessRow(PERSON, ROLE, roleCode, permission.name(), null, null);
    }

    private static AccessRow personal(Permission permission, GrantEffect effect, Instant validUntil) {
        return new AccessRow(PERSON, null, null, permission.name(), effect, validUntil);
    }

    @Test
    @DisplayName("The roles' permissions plus the personal ALLOWs, each with where it comes from")
    void rolesAndAllowsAddUp() {
        EffectiveAccess access = EffectiveAccess.of(List.of(
                fromRole("CLERK", NEEDED), fromRole("AUDITOR", NEEDED),
                personal(NEEDING, GrantEffect.ALLOW, null)));

        assertThat(access.permissions()).containsExactlyInAnyOrder(NEEDED, NEEDING);
        assertThat(access.granted()).containsExactly(
                new EffectiveAccess.Granted(NEEDED, List.of("AUDITOR", "CLERK"), false),
                new EffectiveAccess.Granted(NEEDING, List.of(), true));
        assertThat(access.removed()).isEmpty();
    }

    @Test
    @DisplayName("A DENY always wins: over a role, over an ALLOW, and it names what it removed")
    void aDenyAlwaysWins() {
        EffectiveAccess access = EffectiveAccess.of(List.of(
                fromRole("CLERK", NEEDED), personal(NEEDED, GrantEffect.DENY, null)));

        assertThat(access.permissions()).isEmpty();
        assertThat(access.removed()).containsExactly(new EffectiveAccess.Removed(NEEDED, EffectiveAccess.Removal.DENIED, Set.of()));
    }

    @Test
    @DisplayName("A permission whose prerequisite a DENY removed goes too: no implied grant, ever")
    void aDeniedPrerequisiteTakesItsDependentsAlong() {
        EffectiveAccess access = EffectiveAccess.of(List.of(
                fromRole("ADMIN", NEEDED), fromRole("ADMIN", NEEDING), personal(NEEDED, GrantEffect.DENY, null)));

        assertThat(access.permissions()).isEmpty();
        assertThat(access.removed()).extracting(EffectiveAccess.Removed::permission, EffectiveAccess.Removed::reason)
                .containsExactly(
                        tuple(NEEDED, EffectiveAccess.Removal.DENIED),
                        tuple(NEEDING, EffectiveAccess.Removal.PREREQUISITE_MISSING));
    }

    @Test
    @DisplayName("A permission given without its prerequisite isn't held: the prerequisite is never filled in")
    void aPrerequisiteIsNeverImplied() {
        EffectiveAccess access = EffectiveAccess.of(List.of(personal(NEEDING, GrantEffect.ALLOW, null)));

        assertThat(access.permissions()).isEmpty();
        assertThat(access.removed()).singleElement().satisfies(removed -> {
            assertThat(removed.reason()).isEqualTo(EffectiveAccess.Removal.PREREQUISITE_MISSING);
            assertThat(removed.missing()).containsExactly(NEEDED);
        });
    }

    @Test
    @DisplayName("A code that names no permission grants nothing")
    void anUnknownCodeGrantsNothing() {
        EffectiveAccess access = EffectiveAccess.of(List.of(new AccessRow(PERSON, ROLE, "OLD", "A_REMOVED_PERMISSION", null, null)));

        assertThat(access.permissions()).isEmpty();
        assertThat(access.removed()).isEmpty();
    }

    @Test
    @DisplayName("For good, a role or an ALLOW with an end doesn't count; a DENY with an end still takes away")
    void forGoodCountsOnlyGrantsWithoutAnEnd() {
        List<AccessRow> rows = List.of(
                new AccessRow(PERSON, ROLE, "TEMP", NEEDED.name(), null, LATER),
                personal(NEEDING, GrantEffect.ALLOW, null));

        assertThat(EffectiveAccess.of(rows).permissions()).containsExactlyInAnyOrder(NEEDED, NEEDING);
        assertThat(EffectiveAccess.forGoodOf(rows).permissions()).isEmpty();
        assertThat(EffectiveAccess.forGoodOf(List.of(fromRole("CLERK", NEEDED), personal(NEEDED, GrantEffect.DENY, LATER))).permissions())
                .isEmpty();
    }
}
