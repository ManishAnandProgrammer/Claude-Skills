package com.example.app.user.repository.custom;

import com.example.app.user.dto.request.TenantMemberFilter;
import com.example.app.user.dto.response.TenantMemberResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface CustomTenantMembershipRepository {

    Page<TenantMemberResponse> findByFilter(TenantMemberFilter filter, UUID tenantId, Pageable pageable);
}
