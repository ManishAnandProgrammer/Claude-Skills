package com.example.app.user.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.pagination.Paging;
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

    /** Every permission and area, for a role editor: from the enums, no statement. */
    public ObjectWrapper<PermissionCatalogueResponse> catalogue() {
        return ObjectWrapper.withObject(mapper.toCatalogue());
    }

    public PaginationWrapper<AccessRoleSummaryResponse> findByFilter(AccessRoleFilter filter) {
        return PaginationWrapper.fromPage(repository.findByFilter(filter, paging.offset(filter, filter.getSort().toSort())));
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
