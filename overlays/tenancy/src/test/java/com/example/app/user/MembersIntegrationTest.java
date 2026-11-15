package com.example.app.user;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.tenancy.TenantScope;
import com.example.app.support.IntegrationTest;
import com.example.app.support.WithoutTestTenant;
import com.example.app.user.api.MemberView;
import com.example.app.user.api.Members;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The {@link Members} facade other modules ask about a person: always the current tenant's members, the
 * same answer for another tenant's member as for an unknown account.
 */
@WithoutTestTenant
class MembersIntegrationTest extends IntegrationTest {

    @Autowired
    private Members members;

    @Test
    @DisplayName("isMember is true for this tenant's member only: false for another tenant's, an unknown and a removed one")
    void isMemberAnswersForTheCurrentTenant() throws Exception {
        TestTenant mine = newTenant();
        TestTenant theirs = newTenant();
        TestUser admin = newUserIn(mine, "ADMIN");
        TestUser member = newUserIn(mine, "USER");
        TestUser removed = newUserIn(mine, "USER");
        TestUser stranger = newUserIn(theirs, "USER");
        deleteAs(admin.accessToken(), "/api/v1/tenant-members/" + removed.id()).andExpect(status().isNoContent());

        assertThat(TenantScope.callAs(mine.id(), () -> members.isMember(member.id()))).isTrue();
        assertThat(TenantScope.callAs(mine.id(), () -> members.isMember(stranger.id()))).isFalse();
        assertThat(TenantScope.callAs(mine.id(), () -> members.isMember(UUID.randomUUID()))).isFalse();
        assertThat(TenantScope.callAs(mine.id(), () -> members.isMember(removed.id()))).isFalse();
        assertThat(TenantScope.callAs(theirs.id(), () -> members.isMember(stranger.id()))).isTrue();
    }

    @Test
    @DisplayName("getMemberView answers this tenant's member; another tenant's is TENANT_MEMBER_NOT_FOUND, like an unknown account")
    void getMemberViewIsScopedToTheCurrentTenant() throws Exception {
        TestTenant mine = newTenant();
        TestTenant theirs = newTenant();
        TestUser member = newUserIn(mine, "USER");
        TestUser stranger = newUserIn(theirs, "USER");

        MemberView view = TenantScope.callAs(mine.id(), () -> members.getMemberView(member.id()));

        assertThat(view).isEqualTo(new MemberView(member.id(), member.email(), "Test User"));
        for (UUID nonMember : List.of(stranger.id(), UUID.randomUUID())) {
            assertThatThrownBy(() -> TenantScope.callAs(mine.id(), () -> members.getMemberView(nonMember)))
                    .isInstanceOfSatisfying(AppException.class,
                            refusal -> assertThat(refusal.getErrorCode()).isEqualTo(ErrorCode.TENANT_MEMBER_NOT_FOUND));
        }
    }

    @Test
    @DisplayName("getMemberViews answers this tenant's members among the ids in one statement; the others are simply absent")
    void getMemberViewsIsOneStatement() throws Exception {
        TestTenant mine = newTenant();
        TestTenant theirs = newTenant();
        TestUser first = newUserIn(mine, "USER");
        TestUser second = newUserIn(mine, "ADMIN");
        TestUser stranger = newUserIn(theirs, "USER");

        statistics().clear();
        List<MemberView> views = TenantScope.callAs(mine.id(),
                () -> members.getMemberViews(List.of(first.id(), second.id(), stranger.id(), UUID.randomUUID())));
        long statements = statistics().getPrepareStatementCount();

        assertThat(views).extracting(MemberView::userId).containsExactlyInAnyOrder(first.id(), second.id());
        assertThat(views).extracting(MemberView::email).containsExactlyInAnyOrder(first.email(), second.email());
        assertThat(statements).isEqualTo(1);
    }

    @Test
    @DisplayName("getMemberViews of no ids runs no statement")
    void getMemberViewsOfNoIdsRunsNothing() throws Exception {
        TestTenant mine = newTenant();

        statistics().clear();
        List<MemberView> views = TenantScope.callAs(mine.id(), () -> members.getMemberViews(List.of()));

        assertThat(views).isEmpty();
        assertThat(statistics().getPrepareStatementCount()).isZero();
    }
}
