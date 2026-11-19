package com.example.app.user.service;

import com.example.app.common.dropdown.DropdownFilter;
import com.example.app.common.dropdown.DropdownFor;
import com.example.app.common.dropdown.DropdownItem;
import com.example.app.common.dropdown.DropdownStatus;
import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.exception.RequestError;
import com.example.app.common.pagination.CursorBatch;
import com.example.app.common.pagination.Paging;
import com.example.app.common.security.CurrentUser;
import com.example.app.common.security.Permission;
import com.example.app.common.security.Reach;
import com.example.app.common.wrapper.CursorWrapper;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.common.wrapper.PaginationWrapper;
import com.example.app.user.domain.AccessRole;
import com.example.app.user.domain.Role;
import com.example.app.user.dto.request.AccessRoleCreateRequest;
import com.example.app.user.dto.request.AccessRoleFilter;
import com.example.app.user.dto.request.AccessRoleUpdateRequest;
import com.example.app.user.dto.response.AccessRoleResponse;
import com.example.app.user.dto.response.AccessRoleSummaryResponse;
import com.example.app.user.dto.response.PermissionCatalogueResponse;
import com.example.app.user.mapper.AccessRoleMapper;
import com.example.app.user.repository.AccessRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * The roles (the roles piece): their reads, and the writes' own halves, which {@link AccessRoleManager} calls in its
 * transaction once it has checked who may make them (safety rules 1, 4 and 6 are its). A role's code never
 * changes; a built-in role is edited like any other (its copy), never deleted.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccessRoleService {

    private final AccessRoleRepository repository;
    private final AccessRoleMapper mapper;
    private final Paging paging;
    private final CurrentUser currentUser;

    /** Every permission and area, for a role editor: from the enums, no statement. */
    public ObjectWrapper<PermissionCatalogueResponse> catalogue() {
        return ObjectWrapper.withObject(mapper.toCatalogue());
    }

    public PaginationWrapper<AccessRoleSummaryResponse> findByFilter(AccessRoleFilter filter) {
        return PaginationWrapper.fromPage(repository.findByFilter(filter, paging.offset(filter, filter.getSort().toSort())));
    }

    /**
     * The roles the action {@code action} may use ({@link DropdownFor}; every role is in reach, {@code ALL}), narrowed in
     * the same statement to those the caller may give (safety rule 1: each of their permissions is one the caller holds
     * now, which is the request's own read, no statement), so someone who gives roles without managing them can pick
     * one. Roles have no active flag, so a {@code status} other than the default is refused, not ignored.
     */
    public CursorWrapper<DropdownItem> dropdown(Permission action, DropdownFilter filter) {
        DropdownFor.check(action, currentUser.permissions(), EnumSet.of(Reach.ALL));
        if (filter.getStatus() != DropdownStatus.ACTIVE) {
            throw AppException.withErrors(ErrorCode.REQUEST_PARAMETER_INVALID,
                    List.of(RequestError.atParameter("status", RequestError.INVALID_VALUE)));
        }
        List<String> held = new ArrayList<>();
        for (Permission permission : currentUser.permissions()) {
            held.add(permission.name());
        }
        CursorBatch<DropdownItem> batch = repository.findDropdownItems(filter, held, paging.cursorSize(filter));
        return CursorWrapper.of(batch.items(), batch.nextCursor());
    }

    public ObjectWrapper<AccessRoleResponse> findById(UUID id) {
        return ObjectWrapper.withObject(mapper.toResponse(getRole(id)));
    }

    /** The role with its permissions; 404 {@code ROLE_NOT_FOUND}. */
    public AccessRole getRole(UUID id) {
        return repository.findWithPermissionsById(id).orElseThrow(AppException.supply(ErrorCode.ROLE_NOT_FOUND, id));
    }

    /**
     * The copy of a built-in role (signup's {@code USER}). Seeded by changeset 0037 and never deleted
     * ({@link #delete}), so a missing one is a broken database, not a request's fault.
     */
    AccessRole getBuiltIn(Role role) {
        return repository.findBuiltIn(role.name())
                .orElseThrow(() -> new IllegalStateException("The built-in role %s is missing: changeset 0037 seeds it".formatted(role)));
    }

    // tenancy-row:begin
    /**
     * A new tenant's built-in roles, copied from the {@link Role} enum as it is now, in its activating transaction
     * (row mode: the roles changeset ran before the tenant existed, so it seeded only the tenants that existed then).
     * Each one only when missing: that changeset gave them to every tenant then, a PENDING one included, and a
     * provisioning tried again finds the first try's.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void copyBuiltInsFromTheEnum() {
        for (Role role : Role.values()) {
            if (repository.findBuiltIn(role.name()).isEmpty()) {
                repository.save(AccessRole.copyOf(role));
            }
        }
        repository.flush();
    }

    // tenancy-row:end
    /** A taken code is refused by {@code uq_roles_code} at the flush: 409 {@code ROLE_CODE_TAKEN}. */
    @Transactional(propagation = Propagation.MANDATORY)
    AccessRole create(AccessRoleCreateRequest request) {
        return repository.saveAndFlush(AccessRole.create(request.code(), request.name(), request.permissions()));
    }

    /** 404 {@code ROLE_NOT_FOUND}, 409 {@code OPTIMISTIC_LOCK_CONFLICT}; flushed, so the caller counts what it left. */
    @Transactional(propagation = Propagation.MANDATORY)
    AccessRole update(UUID id, AccessRoleUpdateRequest request) {
        AccessRole role = getRole(id);
        role.checkVersion(request.version());
        role.rename(request.name());
        role.replacePermissions(request.permissions());
        repository.flush();
        return role;
    }

    /**
     * 409 {@code ROLE_BUILT_IN} for a built-in role; one somebody holds is refused at the flush by
     * {@code fk_user_roles_role_id}: 409 {@code ROLE_IN_USE}, and no assignment goes with it.
     */
    @Transactional
    public void delete(UUID id) {
        AccessRole role = getRole(id);
        if (role.isBuiltIn()) {
            throw AppException.of(ErrorCode.ROLE_BUILT_IN);
        }
        repository.delete(role);
        repository.flush();
    }

    AccessRoleResponse toResponse(AccessRole role) {
        return mapper.toResponse(role);
    }
}
