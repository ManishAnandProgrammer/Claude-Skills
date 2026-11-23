package com.example.app.user.repository.custom;

import com.example.app.common.dropdown.DropdownFilter;
import com.example.app.common.dropdown.DropdownItem;
import com.example.app.common.pagination.CursorBatch;
import com.example.app.user.dto.request.AccessRoleFilter;
import com.example.app.user.dto.response.AccessRoleSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;

public interface CustomAccessRoleRepository {

    Page<AccessRoleSummaryResponse> findByFilter(AccessRoleFilter filter, Pageable pageable);

    CursorBatch<DropdownItem> findGivableDropdownItems(DropdownFilter filter, Collection<String> giverPermissionCodes, int size);
}
