package com.example.app.user;

import com.example.app.common.security.Permission;
import com.example.app.support.RolesFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A person's roles and personal grants (the roles piece, house-review AUTHZ-12): the explanation an admin reads, the
 * changes, and the safety rules about a person (1, 2, 3, 7).
 */
class PersonAccessIntegrationTest extends RolesFixtures {

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static String grantJson(String effect) {
        return """
                {"effect": "%s"}""".formatted(effect);
    }

    @Test
    @DisplayName("A person's access shows their roles and grants, what they hold with its source, and what a DENY took")
    void accessExplainsWhereEachPermissionComesFrom() throws Exception {
        String admin = newAdmin().accessToken();
        TestUser person = newUser();
        String role = createRole(admin, NEEDED, NEEDING);
        assign(admin, person.id(), role, LATER).andExpect(status().isOk());
        putAs(admin, userPermission(person.id(), NEEDING), grantJson("DENY")).andExpect(status().isOk());

        getAs(admin, userAccess(person.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.userId").value(person.id().toString()))
                .andExpect(jsonPath("$.response.roles[*].code", hasItem("USER")))
                .andExpect(jsonPath("$.response.roles[?(@.roleId == '%s')].validUntil".formatted(role)).value(hasItem(LATER.toString())))
                .andExpect(jsonPath("$.response.grants[*].permission", contains(NEEDING.name())))
                .andExpect(jsonPath("$.response.grants[0].effect").value("DENY"))
                .andExpect(jsonPath("$.response.held[?(@.permission == '%s')].personallyAllowed".formatted(NEEDED)).value(hasItem(false)))
                .andExpect(jsonPath("$.response.removed[0].permission").value(NEEDING.name()))
                .andExpect(jsonPath("$.response.removed[0].reason").value("DENIED"));
        getAs(admin, userAccess(UUID.randomUUID())).andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("USER_NOT_FOUND"));
    }

    @Test
    @DisplayName("A role given, its end changed, taken: each answers or applies at once, and taking one not held changes nothing")
    void aRoleIsGivenChangedAndTaken() throws Exception {
        String admin = newAdmin().accessToken();
        TestUser person = newUser();
        String role = createRole(admin, NEEDED);

        assign(admin, person.id(), role, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.held[*].permission", hasItem(NEEDED.name())));
        assign(admin, person.id(), role, LATER).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from user_roles where user_id = ? and role_id = ?::uuid", Integer.class,
                person.id(), role)).isEqualTo(1);

        deleteAs(admin, userRole(person.id(), role)).andExpect(status().isNoContent());
        deleteAs(admin, userRole(person.id(), role)).andExpect(status().isNoContent());
        assertThat(myPermissions(person.accessToken())).doesNotContain(NEEDED.name());
        assign(admin, person.id(), UUID.randomUUID().toString(), null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("ROLE_NOT_FOUND"));
    }

    @Test
    @DisplayName("Safety rule 1: a role or a grant gives only what the caller holds (403 ACCESS_NOT_HELD)")
    void onlyWhatTheCallerHolds() throws Exception {
        String admin = newAdmin().accessToken();
        TestUser granter = newUserHolding(Permission.ACCESS_GRANT, NEEDED);
        TestUser person = newUser();
        String bigger = createRole(admin, NEEDED, NEEDING);

        assign(granter.accessToken(), person.id(), bigger, null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("ACCESS_NOT_HELD"));
        putAs(granter.accessToken(), userPermission(person.id(), NEEDING), grantJson("ALLOW"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("ACCESS_NOT_HELD"));
        // The granter holds NEEDED until a date: they give it until a date, never for good.
        putAs(granter.accessToken(), userPermission(person.id(), NEEDED), grantJson("ALLOW"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("ACCESS_NOT_HELD"));
        putAs(granter.accessToken(), userPermission(person.id(), NEEDED), """
                {"effect": "ALLOW", "validUntil": "%s"}""".formatted(LATER)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Safety rule 2: nobody changes their own roles or grants (403 ACCESS_OWN_CHANGE)")
    void nobodyChangesTheirOwnAccess() throws Exception {
        TestUser admin = newAdmin();
        String role = createRole(admin.accessToken(), NEEDED);

        assign(admin.accessToken(), admin.id(), role, null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("ACCESS_OWN_CHANGE"));
        putAs(admin.accessToken(), userPermission(admin.id(), NEEDED), grantJson("DENY"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("ACCESS_OWN_CHANGE"));
        deleteAs(admin.accessToken(), userPermission(admin.id(), NEEDED))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("ACCESS_OWN_CHANGE"));
        deleteAs(admin.accessToken(), userRole(admin.id(), role))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("ACCESS_OWN_CHANGE"));
    }

    @Test
    @DisplayName("Safety rule 3: nobody manages a person who holds a permission they don't (403 ACCESS_TARGET_HOLDS_MORE)")
    void nobodyManagesSomeoneAboveThem() throws Exception {
        TestUser granter = newUserHolding(Permission.ACCESS_GRANT, NEEDED);
        TestUser admin = newAdmin();

        putAs(granter.accessToken(), userPermission(admin.id(), NEEDED), grantJson("DENY"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("ACCESS_TARGET_HOLDS_MORE"));
        assertThat(myPermissions(admin.accessToken())).contains(NEEDED.name());
    }

    @Test
    @DisplayName("A granter whose ACCESS_GRANT was denied is 403 on their next request, with the token they already had")
    void aDeniedGranterIsRefusedAtOnce() throws Exception {
        String admin = newAdmin().accessToken();
        TestUser granter = newUserHolding(Permission.ACCESS_GRANT, NEEDED);
        TestUser person = newUser();
        putAs(admin, userPermission(granter.id(), Permission.ACCESS_GRANT), grantJson("DENY")).andExpect(status().isOk());

        putAs(granter.accessToken(), userPermission(person.id(), NEEDED), grantJson("ALLOW"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("Safety rule 7: two admins changing each other at once queue on their rows; the second finds itself denied")
    void twoChangesOfOnePersonQueue() throws Exception {
        TestUser first = newAdmin();
        TestUser second = newAdmin();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            // Holds both admins' rows, so both changes wait at their lock and none reads anything first.
            Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.queryForList("select id from users where id in (?, ?) order by id for update", first.id(), second.id());
                locked.countDown();
                awaitQuietly(release);
            }));
            awaitLatch(locked);
            Future<Integer> firstDeniesSecond = executor.submit(() -> putAs(first.accessToken(),
                    userPermission(second.id(), Permission.ACCESS_GRANT), grantJson("DENY")).andReturn().getResponse().getStatus());
            Future<Integer> secondDeniesFirst = executor.submit(() -> putAs(second.accessToken(),
                    userPermission(first.id(), Permission.ACCESS_GRANT), grantJson("DENY")).andReturn().getResponse().getStatus());
            awaitLockWaiters(2, null);
            release.countDown();
            within(holder);

            assertThat(List.of(within(firstDeniesSecond), within(secondDeniesFirst))).containsExactlyInAnyOrder(200, 403);
            assertThat(jdbc.queryForObject("""
                    select count(*) from user_permissions
                     where user_id in (?, ?) and permission = 'ACCESS_GRANT' and effect = 'DENY'""", Integer.class,
                    first.id(), second.id())).as("exactly one of the two DENYs was written").isEqualTo(1);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Safety rule 1 on the result: taking a DENY away that would hand back a permission the caller lacks is 403 ACCESS_NOT_HELD")
    void takingADenyAwayHandsBackNothingTheCallerLacks() throws Exception {
        String admin = newAdmin().accessToken();
        TestUser person = newUser();
        assign(admin, person.id(), createRole(admin, NEEDED, NEEDING), null).andExpect(status().isOk());
        putAs(admin, userPermission(person.id(), NEEDED), grantJson("DENY")).andExpect(status().isOk());
        assertThat(myPermissions(person.accessToken())).doesNotContain(NEEDED.name(), NEEDING.name());
        TestUser granter = newUserHolding(Permission.ACCESS_GRANT, NEEDED);

        deleteAs(granter.accessToken(), userPermission(person.id(), NEEDED))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("ACCESS_NOT_HELD"));
        putAs(granter.accessToken(), userPermission(person.id(), NEEDED), grantJson("ALLOW"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("ACCESS_NOT_HELD"));
        assertThat(myPermissions(person.accessToken())).doesNotContain(NEEDED.name(), NEEDING.name());
    }

    @Test
    @DisplayName("Safety rule 3 counts a suspended person's grants: a superior suspended is still above (403 ACCESS_TARGET_HOLDS_MORE)")
    void aSuspendedSuperiorIsStillAbove() throws Exception {
        TestUser superior = newAdmin();
        TestUser granter = newUserHolding(Permission.ACCESS_GRANT, NEEDED);
        String adminRole = jdbc.queryForObject("select id::text from roles where built_in = 'ADMIN'", String.class);
        sql("update users set status = 'SUSPENDED' where id = ?", superior.id());

        deleteAs(granter.accessToken(), userRole(superior.id(), adminRole))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("ACCESS_TARGET_HOLDS_MORE"));
        // tenancy:begin
        // tenancy:else
        // TestUser statusChanger = newUserHolding(Permission.ACCOUNT_READ, Permission.ACCOUNT_STATUS_CHANGE);
        // Long version = jdbc.queryForObject("select version from users where id = ?", Long.class, superior.id());
        // patchAs(statusChanger.accessToken(), "/api/v1/users/" + superior.id() + "/status", """
        //         {"status": "ACTIVE", "version": %d}""".formatted(version))
        //         .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("ACCESS_TARGET_HOLDS_MORE"));
        // tenancy:end
    }

    @Test
    @DisplayName("An assignment's end must lie ahead: a past validUntil is 400 VALIDATION_FAILED")
    void aPastEndIsRefused() throws Exception {
        String admin = newAdmin().accessToken();
        TestUser person = newUser();

        assign(admin, person.id(), createRole(admin, NEEDED), Instant.parse("2000-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }
}
