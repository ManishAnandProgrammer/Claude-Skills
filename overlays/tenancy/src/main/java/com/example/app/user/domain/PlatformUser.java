package com.example.app.user.domain;

import com.example.app.common.persistence.AuditableEntity;
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
import java.util.Objects;

/**
 * A platform staff member (the tenancy piece): separate from tenant users, with no tenant, their own
 * login and tokens for {@code /api/v1/platform/**} only. No endpoint creates or deactivates one: an operator
 * writes the row (docs/tenancy-runbook.md, "Platform staff").
 */
@Entity
@Table(name = "platform_users")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlatformUser extends AuditableEntity {

    @Column(nullable = false)
    private String email;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlatformRole role;

    /** Set by an operator when the person leaves: no sign-in, and every platform request refused, from then on. */
    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    public boolean isActive() {
        return Objects.isNull(deactivatedAt);
    }
}
