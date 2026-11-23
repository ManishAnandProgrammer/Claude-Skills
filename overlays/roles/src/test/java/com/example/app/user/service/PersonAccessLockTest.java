package com.example.app.user.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.security.Permission;
// tenancy:begin
import com.example.app.common.tenancy.TenantScope;
import com.example.app.tenant.api.Tenants;
import com.example.app.user.domain.Role;
// tenancy:end
import com.example.app.user.mapper.PersonAccessMapper;
import com.example.app.user.repository.PersonalGrantRepository;
import com.example.app.user.repository.RoleAssignmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
// tenancy:begin
// tenancy:else
// import org.mockito.ArgumentCaptor;
// tenancy:end

import java.time.Clock;
// tenancy:begin
// tenancy:else
// import java.util.Collection;
// tenancy:end
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
// tenancy:begin
import static org.mockito.Mockito.never;
// tenancy:end
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersonAccessLockTest {

    private final RoleAssignmentRepository assignments = mock(RoleAssignmentRepository.class);
    // tenancy:begin
    private final Tenants tenants = mock(Tenants.class);
    private final MembershipService memberships = mock(MembershipService.class);
    private final PersonAccessService service = new PersonAccessService(assignments, mock(PersonalGrantRepository.class),
            mock(AccessRoleService.class), memberships, tenants, mock(PersonAccessMapper.class), Clock.systemUTC());
    private final UUID tenant = UUID.randomUUID();
    // tenancy:else
    // private final UserService users = mock(UserService.class);
    // private final PersonAccessService service = new PersonAccessService(assignments, mock(PersonalGrantRepository.class),
    //         mock(AccessRoleService.class), users, mock(PersonAccessMapper.class), Clock.systemUTC());
    // tenancy:end

    private void assertDeniedUnderTheLock(Runnable change) {
        when(assignments.findAccessRows(anyCollection(), any())).thenReturn(List.of());
        // tenancy:begin
        assertThatThrownBy(() -> TenantScope.runAs(tenant, change))
        // tenancy:else
        // assertThatThrownBy(change::run)
        // tenancy:end
                .isInstanceOfSatisfying(AppException.class, refused -> assertThat(refused.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
    }

    // tenancy:begin
    @Test
    @DisplayName("Every change of someone's access locks the tenant's row before it reads anyone's access, and locks no member's row")
    void everyChangeLocksTheTenantRowFirst() {
        UUID caller = UUID.randomUUID();
        UUID person = UUID.randomUUID();
        List<Runnable> changes = List.of(
                () -> service.revokeRole(caller, person, UUID.randomUUID()),
                () -> service.lockToManage(caller, person, Permission.TENANT_MEMBERS_MANAGE),
                () -> service.lockToAddMember(caller, Role.USER),
                () -> service.lockManagersWith(caller, Permission.ROLES_MANAGE));

        for (Runnable change : changes) {
            assertDeniedUnderTheLock(change);
        }

        InOrder order = inOrder(tenants, assignments);
        for (int change = 0; change < changes.size(); change++) {
            order.verify(tenants).lockForMemberChange(tenant);
            order.verify(assignments).findAccessRows(anyCollection(), any());
        }
        verify(tenants, times(changes.size())).lockForMemberChange(tenant);
        verify(assignments, never()).findHoldersForGood(anyCollection());
    }
    // tenancy:else
    // @Test
    // @DisplayName("A holder for good who appears between the read and the lock is locked too, before any access is read")
    // void aHolderWhoAppearedMeanwhileIsLockedToo() {
    //     UUID holder = UUID.randomUUID();
    //     UUID newcomer = UUID.randomUUID();
    //     when(assignments.findHoldersForGood(anyCollection()))
    //             .thenReturn(List.of(holder), List.of(holder, newcomer), List.of(holder, newcomer));
    //
    //     assertDeniedUnderTheLock(() -> service.revokeRole(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
    //
    //     @SuppressWarnings("unchecked")
    //     ArgumentCaptor<Collection<UUID>> locked = ArgumentCaptor.forClass(Collection.class);
    //     verify(users, times(2)).lockForAccessChange(locked.capture());
    //     assertThat(locked.getValue()).contains(holder, newcomer);
    //     InOrder order = inOrder(users, assignments);
    //     order.verify(users, times(2)).lockForAccessChange(anyCollection());
    //     order.verify(assignments).findAccessRows(anyCollection(), any());
    // }
    //
    // @Test
    // @DisplayName("Every change of someone's access locks before it reads anyone's access")
    // void everyChangeLocksFirst() {
    //     UUID caller = UUID.randomUUID();
    //     UUID person = UUID.randomUUID();
    //     when(assignments.findHoldersForGood(anyCollection())).thenReturn(List.of());
    //     List<Runnable> changes = List.of(
    //             () -> service.revokeRole(caller, person, UUID.randomUUID()),
    //             () -> service.lockToManage(caller, person, Permission.ACCOUNT_STATUS_CHANGE),
    //             () -> service.lockManagersWith(caller, Permission.ROLES_MANAGE));
    //
    //     for (Runnable change : changes) {
    //         assertDeniedUnderTheLock(change);
    //     }
    //
    //     InOrder order = inOrder(users, assignments);
    //     for (int change = 0; change < changes.size(); change++) {
    //         order.verify(users).lockForAccessChange(anyCollection());
    //         order.verify(assignments).findAccessRows(anyCollection(), any());
    //     }
    // }
    // tenancy:end
}
