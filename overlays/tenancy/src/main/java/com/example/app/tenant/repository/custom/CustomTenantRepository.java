package com.example.app.tenant.repository.custom;

import com.example.app.tenant.dto.request.TenantFilter;
import com.example.app.tenant.dto.response.TenantResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface CustomTenantRepository {

    Page<TenantResponse> findByFilter(TenantFilter filter, Pageable pageable);
}
