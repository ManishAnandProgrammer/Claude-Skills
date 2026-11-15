package com.example.app.user;

import com.example.app.support.IntegrationTest;
import com.example.app.support.WithoutTestTenant;
import com.example.app.user.domain.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A tenant ADMIN reads their tenant's members: the list and one member, each with the version a role
 * change sends back. Every test makes its own tenants: the members list is the tenant's whole team.
 */
@WithoutTestTenant
class TenantMemberListIntegrationTest extends IntegrationTest {

    private static final String MEMBERS = "/api/v1/tenant-members";

    /** An account that signed up and was added through the API: its membership id is the application's (UUIDv7). */
    private UUID addedThroughTheApi(TestUser admin, String emailPrefix, String role) throws Exception {
        String email = uniqueEmail(emailPrefix);
        rawSignup(email, PASSWORD).andExpect(status().isCreated());
        ResultActions added = postAs(admin.accessToken(), MEMBERS, """
                {"email": "%s", "role": "%s"}
                """.formatted(email, role)).andExpect(status().isCreated());
        return UUID.fromString(read(added, "$.response.userId"));
    }

    private ResultActions getWithQuery(TestUser admin, String query) throws Exception {
        return mvc.perform(get(MEMBERS).param("query", query).header("Authorization", "Bearer " + admin.accessToken()));
    }

    @Test
    @DisplayName("The list holds this tenant's members only, and pageInfo counts only them")
    void listShowsOnlyThisTenantsMembers() throws Exception {
        TestTenant mine = newTenant();
        TestTenant theirs = newTenant();
        TestUser admin = newUserIn(mine, "ADMIN");
        TestUser colleague = newUserIn(mine, "USER");
        TestUser stranger = newUserIn(theirs, "ADMIN");
        UUID owner = jdbc.queryForObject("select user_id from tenant_memberships where tenant_id = ? and user_id not in (?, ?)",
                UUID.class, mine.id(), admin.id(), colleague.id());

        getAs(admin.accessToken(), MEMBERS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].userId", containsInAnyOrder(
                        owner.toString(), admin.id().toString(), colleague.id().toString())))
                .andExpect(jsonPath("$.pageInfo.totalElements").value(3));
        getAs(stranger.accessToken(), MEMBERS)
                .andExpect(jsonPath("$.response[*].userId", hasItem(stranger.id().toString())))
                .andExpect(jsonPath("$.response[*].userId", not(hasItem(admin.id().toString()))))
                .andExpect(jsonPath("$.pageInfo.totalElements").value(2));
    }

    @Test
    @DisplayName("A member's GET answers the row: account id, email, name, role, version and the audit fields")
    void getReturnsTheMember() throws Exception {
        TestTenant tenant = newTenant();
        TestUser admin = newUserIn(tenant, "ADMIN");
        UUID colleague = addedThroughTheApi(admin, "colleague", "USER");
        String email = jdbc.queryForObject("select email from users where id = ?", String.class, colleague);

        getAs(admin.accessToken(), MEMBERS + "/" + colleague)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.userId").value(colleague.toString()))
                .andExpect(jsonPath("$.response.email").value(email))
                .andExpect(jsonPath("$.response.fullName").value("Test User"))
                .andExpect(jsonPath("$.response.role").value("USER"))
                .andExpect(jsonPath("$.response.version").value(0))
                .andExpect(jsonPath("$.response.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.response.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.response.createdBy").value(admin.id().toString()))
                .andExpect(jsonPath("$.response.updatedBy").value(admin.id().toString()));
        getAs(admin.accessToken(), MEMBERS + "?ids=" + colleague)
                .andExpect(jsonPath("$.response[0].email").value(email))
                .andExpect(jsonPath("$.response[0].version").value(0))
                .andExpect(jsonPath("$.response[0].createdBy").value(admin.id().toString()));
    }

    @Test
    @DisplayName("Another tenant's member, an unknown account and a removed member are 404 TENANT_MEMBER_NOT_FOUND")
    void getOfANonMemberIs404() throws Exception {
        TestTenant mine = newTenant();
        TestTenant theirs = newTenant();
        TestUser admin = newUserIn(mine, "ADMIN");
        TestUser stranger = newUserIn(theirs, "USER");
        UUID removed = addedThroughTheApi(admin, "leaver", "USER");
        deleteAs(admin.accessToken(), MEMBERS + "/" + removed).andExpect(status().isNoContent());

        for (UUID nonMember : new UUID[] {stranger.id(), UUID.randomUUID(), removed}) {
            String body = getAs(admin.accessToken(), MEMBERS + "/" + nonMember)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("TENANT_MEMBER_NOT_FOUND"))
                    .andReturn().getResponse().getContentAsString();
            assertNoInternals(body);
        }
        getAs(newUserIn(theirs, "ADMIN").accessToken(), MEMBERS + "/" + stranger.id()).andExpect(status().isOk());
    }

    @Test
    @DisplayName("A removed member is not listed; their row in the tenant's own data stays, marked left")
    void aRemovedMemberIsNotListed() throws Exception {
        TestTenant tenant = newTenant();
        TestUser admin = newUserIn(tenant, "ADMIN");
        UUID staying = addedThroughTheApi(admin, "stayer", "USER");
        UUID removed = addedThroughTheApi(admin, "leaver", "USER");
        getAs(admin.accessToken(), MEMBERS).andExpect(jsonPath("$.response[*].userId", hasItem(removed.toString())));

        deleteAs(admin.accessToken(), MEMBERS + "/" + removed).andExpect(status().isNoContent());

        getAs(admin.accessToken(), MEMBERS)
                .andExpect(jsonPath("$.response[*].userId", hasItem(staying.toString())))
                .andExpect(jsonPath("$.response[*].userId", not(hasItem(removed.toString()))));
        getAs(admin.accessToken(), MEMBERS + "?ids=" + removed).andExpect(jsonPath("$.response", empty()));
        assertThat(queryIn(tenant, "select left_at from members where user_id = ?", removed).getFirst().get("left_at")).isNotNull();
    }

    @Test
    @DisplayName("The version a row answers changes the role (200); the same version again is 409 OPTIMISTIC_LOCK_CONFLICT")
    void theListsVersionChangesTheRole() throws Exception {
        TestTenant tenant = newTenant();
        TestUser admin = newUserIn(tenant, "ADMIN");
        UUID colleague = addedThroughTheApi(admin, "colleague", "USER");
        // A sign-in in between: it stamps the membership, and the version the list answers stays current.
        login(jdbc.queryForObject("select email from users where id = ?", String.class, colleague), PASSWORD)
                .andExpect(status().isOk());
        Integer listed = read(getAs(admin.accessToken(), MEMBERS + "?ids=" + colleague), "$.response[0].version");
        Integer detailed = read(getAs(admin.accessToken(), MEMBERS + "/" + colleague), "$.response.version");
        assertThat(detailed).isEqualTo(listed);

        putAs(admin.accessToken(), MEMBERS + "/" + colleague, """
                {"role": "ADMIN", "version": %d}
                """.formatted(listed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.version").value(listed + 1));
        String body = putAs(admin.accessToken(), MEMBERS + "/" + colleague, """
                {"role": "USER", "version": %d}
                """.formatted(listed))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK_CONFLICT"))
                .andReturn().getResponse().getContentAsString();
        assertNoInternals(body);
        getAs(admin.accessToken(), MEMBERS + "/" + colleague)
                .andExpect(jsonPath("$.response.role").value("ADMIN"))
                .andExpect(jsonPath("$.response.version").value(listed + 1));
    }

    @Test
    @DisplayName("A USER member is 403 ACCESS_DENIED on the list and a member's GET; without a token 401")
    void onlyAdminsReadTheMembers() throws Exception {
        TestTenant tenant = newTenant();
        TestUser admin = newUserIn(tenant, "ADMIN");
        TestUser member = newUserIn(tenant, "USER");

        getAs(member.accessToken(), MEMBERS)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        getAs(member.accessToken(), MEMBERS + "/" + admin.id())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        getAs(null, MEMBERS)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        getAs(null, MEMBERS + "/" + admin.id())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("The list is offset-paged: pageInfo describes each page, a page past the last is empty; a size over the maximum is 400")
    void listIsOffsetPaged() throws Exception {
        TestTenant tenant = newTenant();
        TestUser admin = newUserIn(tenant, "ADMIN");
        UUID first = addedThroughTheApi(admin, "first", "USER");
        UUID second = addedThroughTheApi(admin, "second", "USER");
        UUID third = addedThroughTheApi(admin, "third", "USER");
        String ids = String.join(",", first.toString(), second.toString(), third.toString());

        getAs(admin.accessToken(), MEMBERS + "?sort=OLDEST&size=2&ids=" + ids)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].userId", contains(first.toString(), second.toString())))
                .andExpect(jsonPath("$.pageInfo.number").value(0))
                .andExpect(jsonPath("$.pageInfo.size").value(2))
                .andExpect(jsonPath("$.pageInfo.totalElements").value(3))
                .andExpect(jsonPath("$.pageInfo.totalPages").value(2))
                .andExpect(jsonPath("$.pageInfo.hasNext").value(true))
                .andExpect(jsonPath("$.pageInfo.hasPrevious").value(false));
        getAs(admin.accessToken(), MEMBERS + "?sort=OLDEST&size=2&page=1&ids=" + ids)
                .andExpect(jsonPath("$.response[*].userId", contains(third.toString())))
                .andExpect(jsonPath("$.pageInfo.hasNext").value(false))
                .andExpect(jsonPath("$.pageInfo.hasPrevious").value(true));
        getAs(admin.accessToken(), MEMBERS + "?page=5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", empty()))
                .andExpect(jsonPath("$.pageInfo.number").value(5))
                .andExpect(jsonPath("$.pageInfo.totalElements").value(5))
                .andExpect(jsonPath("$.pageInfo.hasPrevious").value(true));
        getAs(admin.accessToken(), MEMBERS + "?size=101")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].parameter", contains("size")))
                .andExpect(jsonPath("$.errors[*].detail", contains("must be at most 100")));
    }

    @Test
    @DisplayName("The configured maximum page index is accepted (an empty page); one more is 400 VALIDATION_FAILED naming page")
    void pageIndexIsBounded() throws Exception {
        TestUser admin = newUserIn(newTenant(), "ADMIN");

        getAs(admin.accessToken(), MEMBERS + "?page=100000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", empty()));
        getAs(admin.accessToken(), MEMBERS + "?page=100001")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].parameter", contains("page")))
                .andExpect(jsonPath("$.errors[*].detail", contains("must be at most 100000")));
    }

    @Test
    @DisplayName("sort=NEWEST (the default) lists the latest to join first; OLDEST the earliest")
    void sortOrdersThePage() throws Exception {
        TestTenant tenant = newTenant();
        TestUser admin = newUserIn(tenant, "ADMIN");
        UUID first = addedThroughTheApi(admin, "first", "ADMIN");
        UUID second = addedThroughTheApi(admin, "second", "USER");
        UUID third = addedThroughTheApi(admin, "third", "ADMIN");
        String ids = "?ids=" + String.join(",", first.toString(), second.toString(), third.toString());

        getAs(admin.accessToken(), MEMBERS + ids)
                .andExpect(jsonPath("$.response[*].userId", contains(third.toString(), second.toString(), first.toString())));
        getAs(admin.accessToken(), MEMBERS + ids + "&sort=NEWEST")
                .andExpect(jsonPath("$.response[*].userId", contains(third.toString(), second.toString(), first.toString())));
        getAs(admin.accessToken(), MEMBERS + ids + "&sort=OLDEST")
                .andExpect(jsonPath("$.response[*].userId", contains(first.toString(), second.toString(), third.toString())));
        getAs(admin.accessToken(), MEMBERS + ids + "&sort=OLDEST&role=ADMIN")
                .andExpect(jsonPath("$.response[*].userId", contains(first.toString(), third.toString())));
        getAs(admin.accessToken(), MEMBERS + ids + "&sort=NEWEST&role=ADMIN")
                .andExpect(jsonPath("$.response[*].userId", contains(third.toString(), first.toString())));
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    @DisplayName("role lists the members with that role only")
    void filterByRoleReturnsOnlyMatching(Role role) throws Exception {
        TestTenant tenant = newTenant();
        TestUser admin = newUserIn(tenant, "ADMIN");
        UUID asked = addedThroughTheApi(admin, "asked", role.name());
        Role other = role == Role.ADMIN ? Role.USER : Role.ADMIN;
        UUID notAsked = addedThroughTheApi(admin, "not-asked", other.name());
        getAs(admin.accessToken(), MEMBERS).andExpect(jsonPath("$.response[*].userId", hasItem(notAsked.toString())));

        getAs(admin.accessToken(), MEMBERS + "?role=" + role.name())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].userId", hasItem(asked.toString())))
                .andExpect(jsonPath("$.response[*].userId", not(hasItem(notAsked.toString()))))
                .andExpect(jsonPath("$.response[*].role", everyItem(is(role.name()))));
    }

    @Test
    @DisplayName("query matches part of the email, ignoring case; % in it matches only a %; another tenant's member never")
    void filterByQueryReturnsOnlyMatching() throws Exception {
        TestTenant mine = newTenant();
        TestTenant theirs = newTenant();
        TestUser admin = newUserIn(mine, "ADMIN");
        String marker = UUID.randomUUID().toString().substring(0, 8);
        UUID matching = addedThroughTheApi(admin, "asha-" + marker, "USER");
        UUID other = addedThroughTheApi(admin, "bilal", "USER");
        UUID percent = addedThroughTheApi(admin, "100%-" + marker, "USER");
        TestUser theirAdmin = newUserIn(theirs, "ADMIN");
        UUID stranger = addedThroughTheApi(theirAdmin, "asha-" + marker, "USER");

        getWithQuery(admin, ("ASHA-" + marker).toUpperCase())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].userId", contains(matching.toString())));
        getWithQuery(admin, marker)
                .andExpect(jsonPath("$.response[*].userId", containsInAnyOrder(matching.toString(), percent.toString())))
                .andExpect(jsonPath("$.response[*].userId", not(hasItem(other.toString()))))
                .andExpect(jsonPath("$.response[*].userId", not(hasItem(stranger.toString()))));
        getWithQuery(admin, "%")
                .andExpect(jsonPath("$.response[*].userId", contains(percent.toString())));
    }

    @Test
    @DisplayName("A query of 100 characters is accepted; 101 is 400 with the configured limit")
    void queryLengthFollowsTheConfiguredLimit() throws Exception {
        TestUser admin = newUserIn(newTenant(), "ADMIN");

        getWithQuery(admin, "ਪ".repeat(100))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", empty()));
        getWithQuery(admin, "q".repeat(101))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].parameter", contains("query")))
                .andExpect(jsonPath("$.errors[*].detail", contains("must be at most 100 characters")));
    }

    @Test
    @DisplayName("ids returns only those members, in the page's order; unknown ids and another tenant's members are simply absent")
    void filterByIdsReturnsOnlyMatching() throws Exception {
        TestTenant mine = newTenant();
        TestTenant theirs = newTenant();
        TestUser admin = newUserIn(mine, "ADMIN");
        UUID first = addedThroughTheApi(admin, "first", "USER");
        UUID second = addedThroughTheApi(admin, "second", "USER");
        UUID notAsked = addedThroughTheApi(admin, "not-asked", "USER");
        TestUser stranger = newUserIn(theirs, "USER");
        getAs(admin.accessToken(), MEMBERS).andExpect(jsonPath("$.response[*].userId", hasItem(notAsked.toString())));

        getAs(admin.accessToken(), MEMBERS + "?ids=" + String.join(",", first.toString(), second.toString(),
                        stranger.id().toString(), UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].userId", contains(second.toString(), first.toString())))
                .andExpect(jsonPath("$.pageInfo.totalElements").value(2));
    }

    @Test
    @DisplayName("ids takes up to the maximum page size; one more is 400 VALIDATION_FAILED, a malformed id 400 REQUEST_PARAMETER_INVALID")
    void idsAreBounded() throws Exception {
        TestUser admin = newUserIn(newTenant(), "ADMIN");

        getAs(admin.accessToken(), MEMBERS + "?ids=" + randomIds(100))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", empty()));
        getAs(admin.accessToken(), MEMBERS + "?ids=" + randomIds(101))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].parameter", contains("ids")))
                .andExpect(jsonPath("$.errors[*].detail", contains("must have at most 100 items")));
        getAs(admin.accessToken(), MEMBERS + "?ids=nope," + UUID.randomUUID())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.errors[*].parameter", contains("ids")))
                .andExpect(jsonPath("$.errors[*].detail", contains("invalid value")));
    }

    @Test
    @DisplayName("An unknown sort or role and a non-numeric page are 400 REQUEST_PARAMETER_INVALID naming only the parameters")
    void unconvertibleFilterValuesAre400() throws Exception {
        TestUser admin = newUserIn(newTenant(), "ADMIN");

        String body = getAs(admin.accessToken(), MEMBERS + "?sort=EMAIL&role=OWNER&page=first")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.errors[*].parameter", containsInAnyOrder("sort", "role", "page")))
                .andExpect(jsonPath("$.errors[*].detail", containsInAnyOrder("invalid value", "invalid value", "invalid value")))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("java.", "com.example", "Exception");
        getAs(admin.accessToken(), MEMBERS + "/not-a-uuid")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"));
    }

    @Test
    @DisplayName("A page costs one statement while partial and two (with the count) when full, whatever the size; a member's GET one")
    void listRunsAConstantNumberOfQueries() throws Exception {
        TestTenant small = newTenant();
        TestUser smallAdmin = newUserIn(small, "ADMIN");
        TestTenant large = newTenant();
        TestUser largeAdmin = newUserIn(large, "ADMIN");
        for (int index = 0; index < 12; index++) {
            newUserIn(large, "USER");
        }

        long forPartialPage = statementsFor(smallAdmin, MEMBERS + "?size=20");
        long forFullPage = statementsFor(largeAdmin, MEMBERS + "?size=10");
        long forFullPageOfMore = statementsFor(largeAdmin, MEMBERS + "?size=5");
        long forIds = statementsFor(largeAdmin, MEMBERS + "?ids=" + String.join(",", largeAdmin.id().toString(), randomIds(50)));
        long forOne = statementsFor(largeAdmin, MEMBERS + "/" + largeAdmin.id());

        assertThat(forPartialPage).isEqualTo(1);
        assertThat(forFullPage).isEqualTo(2);
        assertThat(forFullPageOfMore).isEqualTo(forFullPage);
        assertThat(forIds).isEqualTo(1);
        assertThat(forOne).isEqualTo(1);
    }

    private long statementsFor(TestUser admin, String path) throws Exception {
        // session-version:begin
        // A token's first request reads its session version from the database once (a Redis miss): not the list's.
        getAs(admin.accessToken(), path).andExpect(status().isOk());
        // session-version:end
        statistics().clear();
        getAs(admin.accessToken(), path).andExpect(status().isOk());
        return statistics().getPrepareStatementCount();
    }
}
