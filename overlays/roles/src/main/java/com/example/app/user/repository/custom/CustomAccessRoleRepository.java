package com.example.app.user.repository.custom;

import com.example.app.user.dto.request.AccessRoleFilter;
import com.example.app.user.dto.response.AccessRoleSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface CustomAccessRoleRepository {

    /** A page of roles without their permissions (the detail has them): one statement, plus the count when full. */
    Page<AccessRoleSummaryResponse> findByFilter(AccessRoleFilter filter, Pageable pageable);
}
