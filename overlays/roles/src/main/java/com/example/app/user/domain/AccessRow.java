package com.example.app.user.domain;


import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of a person's access, as the per-request read returns it (RoleAssignmentRepository.findAccessRows): a
 * permission a current role assignment gives ({@code roleId} set, no effect), or a current personal grant
 * ({@code effect} set, no role). Expired rows never come back; {@code validUntil} says whether one will expire.
 */
public record AccessRow(UUID userId, UUID roleId, String roleCode, String permission, GrantEffect effect, Instant validUntil) {

    public boolean isPersonal() {
        return Objects.nonNull(effect);
    }

    public boolean isForGood() {
        return Objects.isNull(validUntil);
    }
}
