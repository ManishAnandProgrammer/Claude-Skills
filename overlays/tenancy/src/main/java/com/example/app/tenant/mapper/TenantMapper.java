package com.example.app.tenant.mapper;

import com.example.app.tenant.api.TenantView;
import com.example.app.tenant.domain.Tenant;
import com.example.app.tenant.dto.response.TenantResponse;
import org.springframework.stereotype.Component;

@Component
public class TenantMapper {

    public TenantResponse toResponse(Tenant tenant) {
        return new TenantResponse(
                tenant.getId(),
                tenant.getName(),
                tenant.getStatus(),
                tenant.getCreatedAt(),
                tenant.getUpdatedAt(),
                tenant.getCreatedBy(),
                tenant.getUpdatedBy(),
                tenant.getVersion());
    }

    public TenantView toView(Tenant tenant) {
        return new TenantView(tenant.getId(), tenant.getName(), tenant.getStatus());
    }
}
