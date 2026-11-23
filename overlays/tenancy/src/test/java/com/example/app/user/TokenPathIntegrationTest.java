package com.example.app.user;

import com.example.app.support.IntegrationTest;
import com.example.app.support.WithoutTestTenant;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
// roles:begin
import java.util.List;
// roles:end
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WithoutTestTenant
class TokenPathIntegrationTest extends IntegrationTest {

    private static final String ME = "/api/v1/users/me";
    private static final String MEMBERS = "/api/v1/tenant-members";
    private static final String PLATFORM_TENANTS = "/api/v1/platform/tenants";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    enum Subject {
        REMOVED_MEMBER,
        DEMOTED_MEMBER,
        SUSPENDED_USER,
        DEACTIVATED_STAFF,
        SUSPENDED_TENANT
    }

    enum Path {
        LOGIN,
        REFRESH,
        SWITCH_TENANT,
        IMPERSONATION,
        NEXT_REQUEST
    }

    private record Person(UUID id, String email, TestTenant tenant, TestTenant otherTenant, String accessToken,
                          String refreshToken, String otherTenantRefreshToken, TestUser owner, String endpointOfItsRole) {
    }

    // session-version:begin
    @ParameterizedTest(name = "{0} × {1}: {2} {3}")
    @CsvSource(delimiter = '|', textBlock = """
            REMOVED_MEMBER    | LOGIN         | 200 | OTHER_TENANT:USER
            REMOVED_MEMBER    | REFRESH       | 404 | MEMBERSHIP_NOT_FOUND
            REMOVED_MEMBER    | SWITCH_TENANT | 404 | MEMBERSHIP_NOT_FOUND
            REMOVED_MEMBER    | IMPERSONATION | 404 | MEMBERSHIP_NOT_FOUND
            REMOVED_MEMBER    | NEXT_REQUEST  | 401 | SESSION_VERSION_STALE
            DEMOTED_MEMBER    | LOGIN         | 200 | TENANT:USER
            DEMOTED_MEMBER    | REFRESH       | 200 | TENANT:USER
            DEMOTED_MEMBER    | SWITCH_TENANT | 200 | TENANT:USER
            DEMOTED_MEMBER    | IMPERSONATION | 201 | TENANT:USER
            // roles:begin
            DEMOTED_MEMBER    | NEXT_REQUEST  | 403 | ACCESS_DENIED
            // roles:else
            // DEMOTED_MEMBER    | NEXT_REQUEST  | 401 | SESSION_VERSION_STALE
            // roles:end
            SUSPENDED_USER    | LOGIN         | 403 | AUTH_ACCOUNT_SUSPENDED
            SUSPENDED_USER    | REFRESH       | 401 | AUTH_INVALID_REFRESH_TOKEN
            SUSPENDED_USER    | SWITCH_TENANT | 401 | AUTH_INVALID_REFRESH_TOKEN
            SUSPENDED_USER    | IMPERSONATION | 403 | AUTH_ACCOUNT_SUSPENDED
            SUSPENDED_USER    | NEXT_REQUEST  | 401 | SESSION_VERSION_STALE
            DEACTIVATED_STAFF | LOGIN         | 401 | AUTH_INVALID_CREDENTIALS
            DEACTIVATED_STAFF | IMPERSONATION | 401 | AUTHENTICATION_REQUIRED
            DEACTIVATED_STAFF | NEXT_REQUEST  | 401 | AUTHENTICATION_REQUIRED
            SUSPENDED_TENANT  | LOGIN         | 200 | OTHER_TENANT:USER
            SUSPENDED_TENANT  | REFRESH       | 403 | TENANT_NOT_ACTIVE
            SUSPENDED_TENANT  | SWITCH_TENANT | 403 | TENANT_NOT_ACTIVE
            SUSPENDED_TENANT  | IMPERSONATION | 403 | TENANT_NOT_ACTIVE
            SUSPENDED_TENANT  | NEXT_REQUEST  | 401 | SESSION_VERSION_STALE
            """)
    // session-version:else
    // // An access token issued before the change lives out its lifetime (at most app.jwt.access-token-ttl)
    // // wherever no request reads the row the change wrote.
    // @ParameterizedTest(name = "{0} × {1}: {2} {3}")
    // @CsvSource(delimiter = '|', textBlock = """
    //         REMOVED_MEMBER    | LOGIN         | 200 | OTHER_TENANT:USER
    //         REMOVED_MEMBER    | REFRESH       | 404 | MEMBERSHIP_NOT_FOUND
    //         REMOVED_MEMBER    | SWITCH_TENANT | 404 | MEMBERSHIP_NOT_FOUND
    //         REMOVED_MEMBER    | IMPERSONATION | 404 | MEMBERSHIP_NOT_FOUND
    //         REMOVED_MEMBER    | NEXT_REQUEST  | 200 | -
    //         DEMOTED_MEMBER    | LOGIN         | 200 | TENANT:USER
    //         DEMOTED_MEMBER    | REFRESH       | 200 | TENANT:USER
    //         DEMOTED_MEMBER    | SWITCH_TENANT | 200 | TENANT:USER
    //         DEMOTED_MEMBER    | IMPERSONATION | 201 | TENANT:USER
    //         // roles:begin
    //         DEMOTED_MEMBER    | NEXT_REQUEST  | 403 | ACCESS_DENIED
    //         // roles:else
    //         DEMOTED_MEMBER    | NEXT_REQUEST  | 200 | -
    //         // roles:end
    //         SUSPENDED_USER    | LOGIN         | 403 | AUTH_ACCOUNT_SUSPENDED
    //         SUSPENDED_USER    | REFRESH       | 401 | AUTH_INVALID_REFRESH_TOKEN
    //         SUSPENDED_USER    | SWITCH_TENANT | 401 | AUTH_INVALID_REFRESH_TOKEN
    //         SUSPENDED_USER    | IMPERSONATION | 403 | AUTH_ACCOUNT_SUSPENDED
    //         SUSPENDED_USER    | NEXT_REQUEST  | 200 | -
    //         DEACTIVATED_STAFF | LOGIN         | 401 | AUTH_INVALID_CREDENTIALS
    //         DEACTIVATED_STAFF | IMPERSONATION | 401 | AUTHENTICATION_REQUIRED
    //         DEACTIVATED_STAFF | NEXT_REQUEST  | 401 | AUTHENTICATION_REQUIRED
    //         SUSPENDED_TENANT  | LOGIN         | 200 | OTHER_TENANT:USER
    //         SUSPENDED_TENANT  | REFRESH       | 403 | TENANT_NOT_ACTIVE
    //         SUSPENDED_TENANT  | SWITCH_TENANT | 403 | TENANT_NOT_ACTIVE
    //         SUSPENDED_TENANT  | IMPERSONATION | 403 | TENANT_NOT_ACTIVE
    //         SUSPENDED_TENANT  | NEXT_REQUEST  | 200 | -
    //         """)
    // session-version:end
    void aChangeOfAccessReachesEveryTokenPath(Subject subject, Path path, int status, String outcome) throws Exception {
        MockHttpServletResponse answer;
        Person person = null;
        if (subject == Subject.DEACTIVATED_STAFF) {
            TestStaff staff = newStaff();
            deactivateStaff(staff.id());
            answer = takeAsStaff(path, staff);
        } else {
            person = newPerson(subject == Subject.DEMOTED_MEMBER ? "ADMIN" : "USER");
            change(subject, person);
            answer = take(path, person);
        }

        assertThat(answer.getStatus()).as(answer.getContentAsString()).isEqualTo(status);
        if (outcome.contains(":")) {
            assertIssuedFor(answer, person, outcome);
        } else if (!outcome.equals("-")) {
            assertThat(JSON.readTree(answer.getContentAsString()).path("errorCode").asString()).isEqualTo(outcome);
        }
    }

    private Person newPerson(String role) throws Exception {
        TestTenant tenant = newTenant();
        TestTenant otherTenant = newTenant();
        TestUser owner = newUserIn(tenant, "ADMIN");
        TestUser member = newUserIn(tenant, role);
        addMember(otherTenant, member.id(), "USER");
        JsonNode inOtherTenant = tokens(switchTenant(member.refreshToken(), otherTenant.id()));
        JsonNode backInTenant = tokens(switchTenant(signIn(member).refreshToken(), tenant.id()));
        String endpointOfItsRole = role.equals("ADMIN") ? MEMBERS : ME;
        return new Person(member.id(), member.email(), tenant, otherTenant, backInTenant.path("accessToken").asString(),
                backInTenant.path("refreshToken").asString(), inOtherTenant.path("refreshToken").asString(), owner,
                endpointOfItsRole);
    }

    private void change(Subject subject, Person person) throws Exception {
        switch (subject) {
            case REMOVED_MEMBER -> deleteAs(person.owner().accessToken(), MEMBERS + "/" + person.id())
                    .andExpect(status().isNoContent());
            // roles:begin
            case DEMOTED_MEMBER -> {
                giveBuiltInRole(person.tenant(), person.id(), "USER");
                String adminRole = queryIn(person.tenant(), "select id::text as id from roles where built_in = 'ADMIN'").getFirst().get("id").toString();
                deleteAs(person.owner().accessToken(), "/api/v1/users/" + person.id() + "/roles/" + adminRole)
                        .andExpect(status().isNoContent());
            }
            // roles:else
            // case DEMOTED_MEMBER -> {
            //     Long version = jdbc.queryForObject("select version from tenant_memberships where tenant_id = ? and user_id = ?",
            //             Long.class, person.tenant().id(), person.id());
            //     putAs(person.owner().accessToken(), MEMBERS + "/" + person.id(), """
            //             {"role": "USER", "version": %d}
            //             """.formatted(version)).andExpect(status().isOk());
            // }
            // roles:end
            case SUSPENDED_USER -> {
                Number version = read(getAs(platformToken(), "/api/v1/platform/users?email=" + person.email())
                        .andExpect(status().isOk()), "$.response[0].version");
                patchAs(platformToken(), "/api/v1/platform/users/" + person.id() + "/status", """
                        {"status": "SUSPENDED", "version": %d}
                        """.formatted(version.longValue())).andExpect(status().isOk());
            }
            case SUSPENDED_TENANT -> {
                Number version = read(getAs(platformToken(), PLATFORM_TENANTS + "/" + person.tenant().id())
                        .andExpect(status().isOk()), "$.response.version");
                patchAs(platformToken(), PLATFORM_TENANTS + "/" + person.tenant().id() + "/status", """
                        {"status": "SUSPENDED", "version": %d}
                        """.formatted(version.longValue())).andExpect(status().isOk());
            }
            case DEACTIVATED_STAFF -> throw new IllegalArgumentException("staff aren't members");
        }
    }

    private MockHttpServletResponse take(Path path, Person person) throws Exception {
        ResultActions answer = switch (path) {
            case LOGIN -> login(person.email(), PASSWORD);
            case REFRESH -> postAs(null, "/api/v1/auth/refresh-token", """
                    {"refreshToken": "%s"}
                    """.formatted(person.refreshToken()));
            case SWITCH_TENANT -> switchTenant(person.otherTenantRefreshToken(), person.tenant().id());
            case IMPERSONATION -> postAs(platformToken(), PLATFORM_TENANTS + "/" + person.tenant().id() + "/impersonations", """
                    {"userId": "%s", "reason": "SUP-1"}
                    """.formatted(person.id()));
            case NEXT_REQUEST -> getAs(person.accessToken(), person.endpointOfItsRole());
        };
        return answer.andReturn().getResponse();
    }

    private MockHttpServletResponse takeAsStaff(Path path, TestStaff staff) throws Exception {
        ResultActions answer = switch (path) {
            case LOGIN -> postAs(null, "/api/v1/platform/auth/login", """
                    {"email": "%s", "password": "%s"}
                    """.formatted(staff.email(), PASSWORD));
            case IMPERSONATION -> postAs(staff.accessToken(), PLATFORM_TENANTS + "/" + newTenant().id() + "/impersonations", """
                    {"reason": "SUP-1"}
                    """);
            case NEXT_REQUEST -> getAs(staff.accessToken(), PLATFORM_TENANTS);
            case REFRESH, SWITCH_TENANT -> throw new IllegalArgumentException("staff have no " + path);
        };
        return answer.andReturn().getResponse();
    }

    private ResultActions switchTenant(String refreshToken, UUID tenantId) throws Exception {
        return postAs(null, "/api/v1/auth/switch-tenant", """
                {"refreshToken": "%s", "tenantId": "%s"}
                """.formatted(refreshToken, tenantId));
    }

    private static JsonNode tokens(ResultActions answer) throws Exception {
        answer.andExpect(status().isOk());
        return JSON.readTree(answer.andReturn().getResponse().getContentAsString()).path("response");
    }

    // roles:begin
    private void assertIssuedFor(MockHttpServletResponse answer, Person person, String outcome) throws Exception {
        String accessToken = JSON.readTree(answer.getContentAsString()).path("response").path("accessToken").asString();
        String payload = new String(Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]), StandardCharsets.UTF_8);
        String tenant = outcome.startsWith("TENANT:") ? person.tenant().id().toString() : person.otherTenant().id().toString();
        Object tenantClaim = JsonPath.read(payload, "$.tenant_id");
        assertThat(tenantClaim).hasToString(tenant);
        assertThat(payload).doesNotContain("\"roles\"");
        List<String> held = read(getAs(accessToken, "/api/v1/users/me/access").andExpect(status().isOk()), "$.response.permissions");
        assertThat(held).as(outcome).doesNotContain("TENANT_MEMBERS_MANAGE");
    }
    // roles:else
    // private static void assertIssuedFor(MockHttpServletResponse answer, Person person, String outcome) throws Exception {
    //     String accessToken = JSON.readTree(answer.getContentAsString()).path("response").path("accessToken").asString();
    //     String payload = new String(Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]), StandardCharsets.UTF_8);
    //     String tenant = outcome.startsWith("TENANT:") ? person.tenant().id().toString() : person.otherTenant().id().toString();
    //     Object tenantClaim = JsonPath.read(payload, "$.tenant_id");
    //     Object roleClaim = JsonPath.read(payload, "$.roles[0]");
    //     assertThat(tenantClaim).hasToString(tenant);
    //     assertThat(roleClaim).hasToString(outcome.substring(outcome.indexOf(':') + 1));
    // }
    // roles:end
}
