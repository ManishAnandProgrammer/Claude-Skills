package com.example.app.user.domain;

// tenancy-row:begin
import com.example.app.common.persistence.TenantOwnedEntity;
// tenancy-row:else
// import com.example.app.common.persistence.AuditableEntity;
// tenancy-row:end
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

/**
 * The tenant's tables reference this row, never the platform's {@code users}, so a tenant's data can move to a
 * database of its own. Never deleted: a removed member's records still reference it.
 */
@Entity
@Table(name = "members")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
// tenancy-row:begin
public class Member extends TenantOwnedEntity {
// tenancy-row:else
// public class Member extends AuditableEntity {
// tenancy-row:end

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "left_at")
    private Instant leftAt;

    public static Member of(UUID userId) {
        return Member.builder()
                .userId(userId)
                .build();
    }

    public void leave(Instant now) {
        leftAt = now;
    }

    public void rejoin() {
        leftAt = null;
    }
}
