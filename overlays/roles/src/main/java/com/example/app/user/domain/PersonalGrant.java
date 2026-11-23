package com.example.app.user.domain;

// tenancy-row:begin
import com.example.app.common.persistence.TenantOwnedEntity;
// tenancy-row:else
// import com.example.app.common.persistence.AuditableEntity;
// tenancy-row:end
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_permissions")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
// tenancy-row:begin
public class PersonalGrant extends TenantOwnedEntity {
// tenancy-row:else
// public class PersonalGrant extends AuditableEntity {
// tenancy-row:end

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false)
    private String permission;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GrantEffect effect;

    @Column(name = "valid_until")
    private Instant validUntil;

    public static PersonalGrant of(UUID userId, String permission, GrantEffect effect, Instant validUntil) {
        return PersonalGrant.builder().userId(userId).permission(permission).effect(effect).validUntil(validUntil).build();
    }

    public void change(GrantEffect newEffect, Instant newValidUntil) {
        effect = newEffect;
        validUntil = newValidUntil;
    }
}
