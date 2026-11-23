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

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccessRoleService {

    private final AccessRoleRepository repository;
    private final AccessRoleMapper mapper;
    private final Paging paging;
    private final CurrentUser currentUser;

    public ObjectWrapper<PermissionCatalogueResponse> catalogue() {
        return ObjectWrapper.withObject(mapper.toCatalogue());
    }

    public PaginationWrapper<AccessRoleSummaryResponse> findByFilter(AccessRoleFilter filter) {
        return PaginationWrapper.fromPage(repository.findByFilter(filter, paging.offset(filter)));
    }

    public CursorWrapper<DropdownItem> dropdown(Permission action, DropdownFilter filter) {
        DropdownFor.requireReach(action, currentUser.permissions(), EnumSet.of(Reach.ALL));
        if (filter.getStatus() != DropdownStatus.ACTIVE) {
            throw AppException.withErrors(ErrorCode.REQUEST_PARAMETER_INVALID,
                    List.of(RequestError.atParameter("status", RequestError.INVALID_VALUE)));
        }
        List<String> giverPermissionCodes = new ArrayList<>();
        for (Permission permission : currentUser.permissions()) {
            giverPermissionCodes.add(permission.name());
        }
        CursorBatch<DropdownItem> batch = repository.findGivableDropdownItems(filter, giverPermissionCodes, paging.cursorSize(filter));
        return CursorWrapper.of(batch.items(), batch.nextCursor());
    }

    public ObjectWrapper<AccessRoleResponse> findById(UUID id) {
        return ObjectWrapper.withObject(mapper.toResponse(getRole(id)));
    }

    public AccessRole getRole(UUID id) {
        return repository.findWithPermissionsById(id).orElseThrow(AppException.supply(ErrorCode.ROLE_NOT_FOUND, id));
    }

    AccessRole getBuiltIn(Role role) {
        return repository.findBuiltIn(role.name())
                .orElseThrow(() -> new IllegalStateException("The built-in role %s is missing: changeset 0037 seeds it".formatted(role)));
    }

    // tenancy-row:begin
    /**
     * Each one only when missing: the roles changeset seeded every tenant that existed then, a PENDING one included,
     * and a provisioning tried again finds the first try's.
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
    @Transactional(propagation = Propagation.MANDATORY)
    AccessRole create(AccessRoleCreateRequest request) {
        return repository.saveAndFlush(AccessRole.create(request.code(), request.name(), request.permissions()));
    }

    /** Flushed, so the caller's count reads what it left. */
    @Transactional(propagation = Propagation.MANDATORY)
    AccessRole update(UUID id, AccessRoleUpdateRequest request) {
        AccessRole role = getRole(id);
        role.checkVersion(request.version());
        role.rename(request.name());
        role.replacePermissions(request.permissions());
        repository.flush();
        return role;
    }

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
