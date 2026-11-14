package com.example.app.user.mapper;

import com.example.app.tenant.api.TenantView;
import com.example.app.user.domain.TenantMembership;
import com.example.app.user.domain.User;
import com.example.app.user.dto.response.MembershipResponse;
import com.example.app.user.dto.response.TenantMemberResponse;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class MembershipMapper {

    public MembershipResponse toResponse(TenantMembership membership, TenantView tenant, UUID currentTenantId) {
        return new MembershipResponse(
                membership.getTenantId(),
                tenant.name(),
                tenant.status().name(),
                membership.getRole(),
                membership.getTenantId().equals(currentTenantId),
                membership.getLastUsedAt(),
                membership.getCreatedAt(),
                membership.getUpdatedAt(),
                membership.getCreatedBy(),
                membership.getUpdatedBy());
    }

    public TenantMemberResponse toMemberResponse(TenantMembership membership) {
        User user = membership.getUser();
        return new TenantMemberResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                membership.getRole(),
                membership.getVersion(),
                membership.getCreatedAt(),
                membership.getUpdatedAt(),
                membership.getCreatedBy(),
                membership.getUpdatedBy());
    }
}
