package com.example.app.user.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
// tenancy:begin

import java.util.List;
import java.util.UUID;
// tenancy:end

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** The roles piece's TokenPermissions on tokens that name nobody it reads: nothing held, and nothing read. */
class RolePermissionsTest {

    private final PersonAccessService access = mock(PersonAccessService.class);
    private final RolePermissions permissions = new RolePermissions(access);

    @Test
    @DisplayName("A token without a subject holds nothing, and reads nothing")
    void aTokenWithoutASubjectHoldsNothing() {
        Jwt token = Jwt.withTokenValue("token").header("alg", "HS256").claim("scope", "none").build();

        assertThat(permissions.of(token)).isEmpty();
        verifyNoInteractions(access);
    }
    // tenancy:begin

    @Test
    @DisplayName("A platform token (staff: no tenant) holds no tenant permission, and reads nothing")
    void aPlatformTokenHoldsNoTenantPermission() {
        Jwt token = Jwt.withTokenValue("token").header("alg", "HS256").subject(UUID.randomUUID().toString())
                .audience(List.of("platform")).build();

        assertThat(permissions.of(token)).isEmpty();
        verifyNoInteractions(access);
    }
    // tenancy:end
}
