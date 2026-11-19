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

    /** A page of roles without their permissions (the detail has them): one statement, plus the count when full. */
    Page<AccessRoleSummaryResponse> findByFilter(AccessRoleFilter filter, Pageable pageable);

    /**
     * A batch of the dropdown's options, by name then id: the roles none of whose permission codes is outside
     * {@code held}, in one statement.
     */
    CursorBatch<DropdownItem> findDropdownItems(DropdownFilter filter, Collection<String> held, int size);
}
