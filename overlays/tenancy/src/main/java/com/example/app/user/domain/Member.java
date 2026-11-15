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
 * A person inside one tenant's own data (house-review TEN-17): what the tenant's tables reference for
 * an owner ({@code owner_id → members}), so no tenant table points into the platform's {@code users}
 * and a tenant's data can move to a database of its own (TEN-16). The platform's membership
 * ({@link TenantMembership}) stays the authority on who belongs and with which role; this row is
 * written in the same transaction as it.
 *
 * <p>Never deleted: a removed member's records still reference the row, so leaving marks it
 * ({@code leftAt}) and coming back clears the mark on the same row. It holds no personal data: the
 * account's email and name stay on the platform.</p>
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

    /** The account's id, the same as {@code users.id}: a copy, not a reference to the platform. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** When the person stopped being a member; {@code null} while they are one. */
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
