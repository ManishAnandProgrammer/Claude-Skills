package com.example.app.tenant.domain;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.persistence.AuditableEntity;
import com.example.app.tenant.api.TenantStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "tenants")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Tenant extends AuditableEntity {

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TenantStatus status;

    public static Tenant create(String name) {
        return Tenant.builder()
                .name(name.strip())
                .status(TenantStatus.PENDING)
                .build();
    }

    public void changeStatus(TenantStatus target) {
        if (!status.canBecome(target)) {
            throw AppException.of(ErrorCode.TENANT_STATUS_CHANGE_INVALID, status, target);
        }
        status = target;
    }

    public boolean isActive() {
        return status == TenantStatus.ACTIVE;
    }
}
