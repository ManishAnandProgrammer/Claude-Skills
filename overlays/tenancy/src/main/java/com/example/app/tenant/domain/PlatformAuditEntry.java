package com.example.app.tenant.domain;

import com.example.app.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

@Entity
@Table(name = "platform_audit")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlatformAuditEntry extends AuditableEntity {

    @Column(name = "actor_id", nullable = false, updatable = false)
    private UUID actorId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String action;

    @Column(updatable = false)
    private String detail;

    public static PlatformAuditEntry of(UUID actorId, UUID tenantId, String action, String detail) {
        return PlatformAuditEntry.builder()
                .actorId(actorId)
                .tenantId(tenantId)
                .action(action)
                .detail(detail)
                .build();
    }
}
