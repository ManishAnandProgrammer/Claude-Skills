package com.example.app.user;

import com.example.app.support.CapturedLog;
import com.example.app.support.IntegrationTest;
import com.example.app.support.WithoutTestTenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WithoutTestTenant
class StaffDeactivationIntegrationTest extends IntegrationTest {

    private static final String TENANTS = "/api/v1/platform/tenants";
    private static final String ME = "/api/v1/users/me";

    private ResultActions deactivate(String token, UUID staffId) throws Exception {
        return postAs(token, "/api/v1/platform/staff/" + staffId + "/deactivation", "");
    }

    private String impersonate(TestStaff staff, TestTenant tenant, TestUser member) throws Exception {
        return read(postAs(staff.accessToken(), TENANTS + "/" + tenant.id() + "/impersonations", """
                {"userId": "%s", "reason": "SUP-2001"}
                """.formatted(member.id())).andExpect(status().isCreated()), "$.response.accessToken");
    }

    private Instant deactivatedAt(UUID staffId) {
        OffsetDateTime at = jdbc.queryForObject("select deactivated_at from platform_users where id = ?", OffsetDateTime.class, staffId);
        return Objects.isNull(at) ? null : at.toInstant();
    }

    private UUID updatedBy(UUID staffId) {
        return jdbc.queryForObject("select updated_by from platform_users where id = ?", UUID.class, staffId);
    }

    @Test
    @DisplayName("""
            Another staff member deactivates one: 200 with the id, email and time, updated_by naming the caller, a log \
            line naming both; the deactivated one's token is refused from then on, the caller's works on \
            (TokenPathIntegrationTest has the deactivated one's paths)""")
    void anotherStaffMemberDeactivatesOne() throws Exception {
        TestStaff leaving = newStaff();
        TestStaff caller = newStaff();
        getAs(leaving.accessToken(), TENANTS).andExpect(status().isOk());

        CapturedLog log = new CapturedLog();
        log.open();
        String deactivatedAt;
        try {
            deactivatedAt = read(deactivate(caller.accessToken(), leaving.id())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.response.id").value(leaving.id().toString()))
                    .andExpect(jsonPath("$.response.email").value(leaving.email())), "$.response.deactivatedAt");
        } finally {
            log.close();
        }

        assertThat(Instant.parse(deactivatedAt)).isEqualTo(deactivatedAt(leaving.id()));
        assertThat(updatedBy(leaving.id())).isEqualTo(caller.id());
        assertThat(log.text()).contains("platformUserId=" + leaving.id() + ", deactivatedBy=" + caller.id());
        getAs(leaving.accessToken(), TENANTS)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        getAs(caller.accessToken(), TENANTS).andExpect(status().isOk());
    }

    @Test
    // session-version:begin
    @DisplayName("""
            An impersonation the staff member started before their deactivation: its token and the impersonated \
            member's own are 401 SESSION_VERSION_STALE at once. Another staff member's impersonation of another member \
            is untouched""")
    // session-version:else
    // @DisplayName("""
    //         An impersonation the staff member started before their deactivation: its token and the impersonated \
    //         member's own work until they expire (an impersonation token issues nothing). Another staff member's \
    //         impersonation of another member works too""")
    // session-version:end
    void anImpersonationStartedBeforeTheDeactivation() throws Exception {
        TestTenant tenant = newTenant();
        TestUser impersonated = newUserIn(tenant);
        TestUser other = newUserIn(tenant);
        TestStaff leaving = newStaff();
        TestStaff staying = newStaff();
        String leavingsImpersonation = impersonate(leaving, tenant, impersonated);
        String stayingsImpersonation = impersonate(staying, tenant, other);
        getAs(leavingsImpersonation, ME).andExpect(status().isOk());

        deactivateStaff(leaving.id());

        // session-version:begin
        getAs(leavingsImpersonation, ME)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("SESSION_VERSION_STALE"));
        getAs(impersonated.accessToken(), ME)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("SESSION_VERSION_STALE"));
        // session-version:else
        // getAs(leavingsImpersonation, ME).andExpect(status().isOk());
        // getAs(impersonated.accessToken(), ME).andExpect(status().isOk());
        // session-version:end
        getAs(stayingsImpersonation, ME).andExpect(status().isOk());
        getAs(other.accessToken(), ME).andExpect(status().isOk());
    }
    // session-version:begin

    @Test
    @DisplayName("""
            Only the impersonations of the last token lifetime are reached: an older one's member keeps their \
            session, and the impersonated member their other tenants'""")
    void anOlderImpersonationAndOtherTenantsAreLeftAlone() throws Exception {
        TestTenant tenant = newTenant();
        TestTenant otherTenant = newTenant();
        TestUser longAgo = newUserIn(tenant);
        TestUser recently = newUserIn(tenant);
        addMember(otherTenant, recently.id(), "USER");
        String inOtherTenant = read(postAs(null, "/api/v1/auth/switch-tenant", """
                {"refreshToken": "%s", "tenantId": "%s"}
                """.formatted(signIn(recently).refreshToken(), otherTenant.id())).andExpect(status().isOk()),
                "$.response.accessToken");
        TestStaff leaving = newStaff();
        impersonate(leaving, tenant, longAgo);
        impersonate(leaving, tenant, recently);
        assertThat(sqlAsOwner("""
                update platform_audit set created_at = now() - interval '1 day'
                 where actor_id = ? and impersonated_user_id = ?""", leaving.id(), longAgo.id())).isOne();

        deactivateStaff(leaving.id());

        getAs(longAgo.accessToken(), ME).andExpect(status().isOk());
        getAs(recently.accessToken(), ME).andExpect(status().isUnauthorized());
        getAs(inOtherTenant, ME).andExpect(status().isOk());
    }
    // session-version:end

    @Test
    @DisplayName("""
            Deactivating someone already deactivated is 200 and changes nothing: the first deactivation's time, \
            version and updated_by stay""")
    void aRepeatedDeactivationChangesNothing() throws Exception {
        TestStaff leaving = newStaff();
        deactivateStaff(leaving.id());
        Instant first = deactivatedAt(leaving.id());
        Long version = jdbc.queryForObject("select version from platform_users where id = ?", Long.class, leaving.id());
        UUID firstDeactivatedBy = updatedBy(leaving.id());
        TestStaff caller = newStaff();

        String answered = read(deactivate(caller.accessToken(), leaving.id()).andExpect(status().isOk()),
                "$.response.deactivatedAt");

        assertThat(Instant.parse(answered)).isEqualTo(first);
        assertThat(deactivatedAt(leaving.id())).isEqualTo(first);
        assertThat(jdbc.queryForObject("select version from platform_users where id = ?", Long.class, leaving.id())).isEqualTo(version);
        assertThat(updatedBy(leaving.id())).isEqualTo(firstDeactivatedBy).isNotEqualTo(caller.id());
    }

    @Test
    @DisplayName("""
            One's own id is 403 PLATFORM_STAFF_OWN_DEACTIVATION, the caller still active; an unknown id is 404 \
            PLATFORM_STAFF_NOT_FOUND""")
    void ownAndUnknownAreRefused() throws Exception {
        TestStaff caller = newStaff();

        deactivate(caller.accessToken(), caller.id())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("PLATFORM_STAFF_OWN_DEACTIVATION"));
        deactivate(caller.accessToken(), UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PLATFORM_STAFF_NOT_FOUND"));

        assertThat(deactivatedAt(caller.id())).isNull();
        getAs(caller.accessToken(), TENANTS).andExpect(status().isOk());
    }

    @Test
    @DisplayName("A deactivated staff member's token deactivates nobody (401), and a tenant token is no platform token (401)")
    void onlyAnActiveStaffMemberDeactivates() throws Exception {
        TestStaff leaving = newStaff();
        TestStaff target = newStaff();
        deactivateStaff(leaving.id());

        deactivate(leaving.accessToken(), target.id())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        deactivate(newUser().accessToken(), target.id()).andExpect(status().isUnauthorized());

        assertThat(deactivatedAt(target.id())).isNull();
    }

    @Test
    @DisplayName("Of two staff members deactivating each other at once, one is deactivated: the second finds itself deactivated")
    void twoStaffMembersDeactivatingEachOtherLeaveOne() throws Exception {
        TestStaff first = newStaff();
        TestStaff second = newStaff();
        CountDownLatch start = new CountDownLatch(1);
        List<Answer> answers;
        try (ExecutorService executor = newExecutor()) {
            Callable<Answer> firstDeactivates = () -> {
                awaitLatch(start);
                return answerOf(deactivate(first.accessToken(), second.id()));
            };
            Callable<Answer> secondDeactivates = () -> {
                awaitLatch(start);
                return answerOf(deactivate(second.accessToken(), first.id()));
            };
            Future<Answer> firstAnswer = executor.submit(firstDeactivates);
            Future<Answer> secondAnswer = executor.submit(secondDeactivates);
            start.countDown();
            answers = List.of(awaitResult(firstAnswer), awaitResult(secondAnswer));
        }

        assertThat(answers).extracting(Answer::status).containsExactlyInAnyOrder(200, 401);
        assertThat(jdbc.queryForObject("select count(*) from platform_users where id in (?, ?) and deactivated_at is not null",
                Integer.class, first.id(), second.id())).isOne();
    }

    @Test
    @DisplayName("""
            The pool's role writes a deactivation and nothing else of a staff row: no reactivation, no other column; \
            the owner role reactivates""")
    void thePoolsRoleOnlyDeactivates() throws Exception {
        TestStaff staff = newStaff();
        deactivateStaff(staff.id());

        assertThatThrownBy(() -> jdbc.update("update platform_users set deactivated_at = null where id = ?", staff.id()))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("reactivated by the owner role only");
        assertThatThrownBy(() -> jdbc.update("update platform_users set password_hash = 'x' where id = ?", staff.id()))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied");
        assertThat(sqlAsOwner("update platform_users set deactivated_at = null where id = ?", staff.id()))
                .isOne();
        assertThat(deactivatedAt(staff.id())).isNull();
    }
}
