package com.example.app.support;

import com.example.app.common.security.Permission;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public abstract class RolesFixtures extends IntegrationTest {

    protected static final String ROLES = "/api/v1/roles";
    protected static final String MY_ACCESS = "/api/v1/users/me/access";
    /** A fixture's grant is current but never for good, so it never counts as a holder for good (safety rule 6). */
    protected static final Instant LATER = Instant.now().plus(3650, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    // tenancy:begin
    protected static final Permission NEEDED = Permission.TENANT_MEMBERS_MANAGE;
    protected static final Permission NEEDING = Permission.ACCESS_GRANT;
    // tenancy:else
    // protected static final Permission NEEDED = Permission.ACCOUNT_READ;
    // protected static final Permission NEEDING = Permission.ACCOUNT_STATUS_CHANGE;
    // tenancy:end

    protected static String uniqueCode() {
        return "R_" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
    }

    protected static String userAccess(UUID userId) {
        return "/api/v1/users/" + userId + "/access";
    }

    protected static String userRole(UUID userId, String roleId) {
        return "/api/v1/users/" + userId + "/roles/" + roleId;
    }

    protected static String userPermission(UUID userId, Permission permission) {
        return "/api/v1/users/" + userId + "/permissions/" + permission;
    }

    protected static String permissionsJson(Permission... permissions) {
        return Arrays.stream(permissions).map(permission -> "\"" + permission + "\"").collect(Collectors.joining(", ", "[", "]"));
    }

    protected static String validUntilJson(Instant validUntil) {
        return validUntil == null ? "{}" : """
                {"validUntil": "%s"}""".formatted(validUntil);
    }

    /** Exactly these permissions: their prerequisites are the caller's to include. */
    protected String createRole(String adminToken, Permission... permissions) throws Exception {
        return read(postAs(adminToken, ROLES, """
                {"code": "%s", "name": "Fixture role", "permissions": %s}
                """.formatted(uniqueCode(), permissionsJson(permissions))).andExpect(status().isCreated()), "$.response.id");
    }

    protected ResultActions assign(String adminToken, UUID userId, String roleId, Instant validUntil) throws Exception {
        return putAs(adminToken, userRole(userId, roleId), validUntilJson(validUntil));
    }

    protected TestUser newUserHolding(Permission... permissions) throws Exception {
        String admin = newAdmin().accessToken();
        TestUser user = newUser();
        assign(admin, user.id(), createRole(admin, permissions), LATER).andExpect(status().isOk());
        return user;
    }

    protected List<String> myPermissions(String token) throws Exception {
        return read(getAs(token, MY_ACCESS).andExpect(status().isOk()), "$.response.permissions");
    }
}
