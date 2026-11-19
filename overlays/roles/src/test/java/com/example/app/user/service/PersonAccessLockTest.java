package com.example.app.user.service;

import com.example.app.common.exception.AppException;
// tenancy:begin
import com.example.app.common.tenancy.TenantScope;
import com.example.app.tenant.api.Tenants;
// tenancy:end
import com.example.app.user.mapper.PersonAccessMapper;
import com.example.app.user.repository.PersonalGrantRepository;
import com.example.app.user.repository.RoleAssignmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

// tenancy:begin
// tenancy:else
// import static org.assertj.core.api.Assertions.assertThat;
// tenancy:end
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
// tenancy:begin
import static org.mockito.Mockito.never;
// tenancy:end
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Safety rule 7's lock is stable (house-review AUTHZ-12): what a change counts can't move once it holds the lock.
 *   <!-- tenancy:begin -->
 * With tenancy that is the tenant's row, one row every access change in the tenant locks first.
 *   <!-- tenancy:else -->
 <!-- * Without tenancy the holders for good are read before their rows are locked: they are read again under the lock, -->
 <!-- * and a holder who appeared meanwhile is locked too, before anything is counted. -->
 *   <!-- tenancy:end -->
 */
class PersonAccessLockTest {

    private final RoleAssignmentRepository assignments = mock(RoleAssignmentRepository.class);
    // tenancy:begin
    private final Tenants tenants = mock(Tenants.class);
    private final MembershipService memberships = mock(MembershipService.class);
    private final PersonAccessService service = new PersonAccessService(assignments, mock(PersonalGrantRepository.class),
            mock(AccessRoleService.class), memberships, tenants, mock(PersonAccessMapper.class), Clock.systemUTC());
    // tenancy:else
    // private final UserService users = mock(UserService.class);
    // private final PersonAccessService service = new PersonAccessService(assignments, mock(PersonalGrantRepository.class),
    //         mock(AccessRoleService.class), users, mock(PersonAccessMapper.class), Clock.systemUTC());
    // tenancy:end

    // tenancy:begin
    @Test
    @DisplayName("Every change of someone's access locks the tenant's row first, and locks no member's row")
    void aChangeLocksTheTenantRow() {
        UUID tenant = UUID.randomUUID();
        UUID caller = UUID.randomUUID();
        when(assignments.findAccessRows(anyCollection(), any())).thenReturn(List.of());

        // The caller holds nothing here: refused once locked, as every rule is checked under the lock.
        assertThatThrownBy(() -> TenantScope.runAs(tenant, () -> service.revokeRole(caller, UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(AppException.class);

        verify(tenants, times(1)).lockForMemberChange(tenant);
        verify(assignments, never()).findHoldersForGood(anyCollection());
    }
    // tenancy:else
    // @Test
    // @DisplayName("A holder for good who appears between the read and the lock is locked too, before any rule is checked")
    // void aHolderWhoAppearedMeanwhileIsLockedToo() {
    //     UUID holder = UUID.randomUUID();
    //     UUID newcomer = UUID.randomUUID();
    //     when(assignments.findHoldersForGood(anyCollection()))
    //             .thenReturn(List.of(holder), List.of(holder, newcomer), List.of(holder, newcomer));
    //     when(assignments.findAccessRows(anyCollection(), any())).thenReturn(List.of());
    //
    //     // The caller holds nothing: refused once locked, as every rule is checked under the lock.
    //     assertThatThrownBy(() -> service.revokeRole(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
    //             .isInstanceOf(AppException.class);
    //
    //     @SuppressWarnings("unchecked")
    //     ArgumentCaptor<Collection<UUID>> locked = ArgumentCaptor.forClass(Collection.class);
    //     verify(users, times(2)).lockForAccessChange(locked.capture());
    //     assertThat(locked.getValue()).contains(holder, newcomer);
    // }
    // tenancy:end
}
