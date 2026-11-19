package com.example.app.user.repository.impl;

// tenancy:begin
import com.example.app.common.tenancy.TenantContext;
// tenancy:end
import com.example.app.user.domain.GrantEffect;
import com.example.app.user.domain.AccessRow;
import com.example.app.user.repository.custom.CustomRoleAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The person's access as rows, in SQL through JDBC: one statement for a request, read before its handler runs, and
 * never counted among the handler's own statements (Hibernate's statistics, which the list tests count, don't see
 * it). It runs inside the caller's read-only transaction, on its connection.
 *   <!-- tenancy:begin -->
 * In the tenant's context: the tenant's tables are those of the current tenant, and the platform's account,
 * membership and tenant register are joined in the same statement, so a suspended account, a removed member or a
 * suspended tenant holds nothing on its next request.
 *   <!-- tenancy:end -->
 */
@Repository
@RequiredArgsConstructor
class RoleAssignmentRepositoryImpl implements CustomRoleAssignmentRepository {

    // tenancy-schema:begin
    // The platform's tables are named with their schema: a tenant's transaction has its own schema alone on the
    // search path (TenancyArchTest allows this class, the one read that joins both in one statement).
    static final String ACCESS_ROWS = """
            SELECT a.user_id, r.id AS role_id, r.code AS role_code, p.permission, NULL AS effect, a.valid_until
              FROM user_roles a
              JOIN role_permissions p ON p.role_id = a.role_id
              JOIN roles r ON r.id = a.role_id
              JOIN platform.users u ON u.id = a.user_id AND (u.status = 'ACTIVE' OR NOT :activeOnly)
              JOIN platform.tenant_memberships m ON m.user_id = a.user_id AND m.tenant_id = :tenantId
              JOIN platform.tenants t ON t.id = m.tenant_id AND (t.status = 'ACTIVE' OR :anyTenantStatus)
             WHERE a.user_id IN (:userIds) AND (a.valid_until IS NULL OR a.valid_until > :now)
            UNION ALL
            SELECT g.user_id, NULL, NULL, g.permission, g.effect, g.valid_until
              FROM user_permissions g
              JOIN platform.users u ON u.id = g.user_id AND (u.status = 'ACTIVE' OR NOT :activeOnly)
              JOIN platform.tenant_memberships m ON m.user_id = g.user_id AND m.tenant_id = :tenantId
              JOIN platform.tenants t ON t.id = m.tenant_id AND (t.status = 'ACTIVE' OR :anyTenantStatus)
             WHERE g.user_id IN (:userIds) AND (g.valid_until IS NULL OR g.valid_until > :now)""";
    // tenancy-schema:end
    // tenancy-row:begin
    // The tenant condition is the statement's own as well as the policy's, so the indexes that lead with the
    // person serve it (TEN-36).
    static final String ACCESS_ROWS = """
            SELECT a.user_id, r.id AS role_id, r.code AS role_code, p.permission, NULL AS effect, a.valid_until
              FROM user_roles a
              JOIN role_permissions p ON p.role_id = a.role_id AND p.tenant_id = :tenantId
              JOIN roles r ON r.id = a.role_id AND r.tenant_id = :tenantId
              JOIN users u ON u.id = a.user_id AND (u.status = 'ACTIVE' OR NOT :activeOnly)
              JOIN tenant_memberships m ON m.user_id = a.user_id AND m.tenant_id = a.tenant_id
              JOIN tenants t ON t.id = m.tenant_id AND (t.status = 'ACTIVE' OR :anyTenantStatus)
             WHERE a.user_id IN (:userIds) AND a.tenant_id = :tenantId
               AND (a.valid_until IS NULL OR a.valid_until > :now)
            UNION ALL
            SELECT g.user_id, NULL, NULL, g.permission, g.effect, g.valid_until
              FROM user_permissions g
              JOIN users u ON u.id = g.user_id AND (u.status = 'ACTIVE' OR NOT :activeOnly)
              JOIN tenant_memberships m ON m.user_id = g.user_id AND m.tenant_id = g.tenant_id
              JOIN tenants t ON t.id = m.tenant_id AND (t.status = 'ACTIVE' OR :anyTenantStatus)
             WHERE g.user_id IN (:userIds) AND g.tenant_id = :tenantId
               AND (g.valid_until IS NULL OR g.valid_until > :now)""";
    // tenancy-row:end
    // tenancy:begin
    // tenancy:else
    // static final String ACCESS_ROWS = """
    //         SELECT a.user_id, r.id AS role_id, r.code AS role_code, p.permission, NULL AS effect, a.valid_until
    //           FROM user_roles a
    //           JOIN role_permissions p ON p.role_id = a.role_id
    //           JOIN roles r ON r.id = a.role_id
    //           JOIN users u ON u.id = a.user_id AND (u.status = 'ACTIVE' OR NOT :activeOnly)
    //          WHERE a.user_id IN (:userIds) AND (a.valid_until IS NULL OR a.valid_until > :now)
    //         UNION ALL
    //         SELECT g.user_id, NULL, NULL, g.permission, g.effect, g.valid_until
    //           FROM user_permissions g
    //           JOIN users u ON u.id = g.user_id AND (u.status = 'ACTIVE' OR NOT :activeOnly)
    //          WHERE g.user_id IN (:userIds) AND (g.valid_until IS NULL OR g.valid_until > :now)""";
    // tenancy:end

    // tenancy-schema:begin
    static final String HOLDERS_FOR_GOOD = """
            SELECT a.user_id
              FROM user_roles a
              JOIN role_permissions p ON p.role_id = a.role_id
              JOIN platform.users u ON u.id = a.user_id AND u.status = 'ACTIVE'
              JOIN platform.tenant_memberships m ON m.user_id = a.user_id AND m.tenant_id = :tenantId
             WHERE p.permission IN (:permissions) AND a.valid_until IS NULL
            UNION
            SELECT g.user_id
              FROM user_permissions g
              JOIN platform.users u ON u.id = g.user_id AND u.status = 'ACTIVE'
              JOIN platform.tenant_memberships m ON m.user_id = g.user_id AND m.tenant_id = :tenantId
             WHERE g.permission IN (:permissions) AND g.effect = 'ALLOW' AND g.valid_until IS NULL""";
    // tenancy-schema:end
    // tenancy-row:begin
    static final String HOLDERS_FOR_GOOD = """
            SELECT a.user_id
              FROM user_roles a
              JOIN role_permissions p ON p.role_id = a.role_id AND p.tenant_id = :tenantId
              JOIN users u ON u.id = a.user_id AND u.status = 'ACTIVE'
              JOIN tenant_memberships m ON m.user_id = a.user_id AND m.tenant_id = :tenantId
             WHERE p.permission IN (:permissions) AND a.tenant_id = :tenantId AND a.valid_until IS NULL
            UNION
            SELECT g.user_id
              FROM user_permissions g
              JOIN users u ON u.id = g.user_id AND u.status = 'ACTIVE'
              JOIN tenant_memberships m ON m.user_id = g.user_id AND m.tenant_id = g.tenant_id
             WHERE g.permission IN (:permissions) AND g.tenant_id = :tenantId AND g.effect = 'ALLOW'
               AND g.valid_until IS NULL""";
    // tenancy-row:end
    // tenancy:begin
    // tenancy:else
    // static final String HOLDERS_FOR_GOOD = """
    //         SELECT a.user_id
    //           FROM user_roles a
    //           JOIN role_permissions p ON p.role_id = a.role_id
    //           JOIN users u ON u.id = a.user_id AND u.status = 'ACTIVE'
    //          WHERE p.permission IN (:permissions) AND a.valid_until IS NULL
    //         UNION
    //         SELECT g.user_id
    //           FROM user_permissions g
    //           JOIN users u ON u.id = g.user_id AND u.status = 'ACTIVE'
    //          WHERE g.permission IN (:permissions) AND g.effect = 'ALLOW' AND g.valid_until IS NULL""";
    // tenancy:end

    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public List<AccessRow> findAccessRows(Collection<UUID> userIds, Instant now) {
        // tenancy:begin
        return accessRows(userIds, now, true, false);
        // tenancy:else
        // return accessRows(userIds, now, true);
        // tenancy:end
    }

    @Override
    public List<AccessRow> findGrantedRows(Collection<UUID> userIds, Instant now) {
        // tenancy:begin
        return accessRows(userIds, now, false, false);
        // tenancy:else
        // return accessRows(userIds, now, false);
        // tenancy:end
    }
    // tenancy:begin

    @Override
    public List<AccessRow> findAccessRowsWhateverTheTenantStatus(Collection<UUID> userIds, Instant now) {
        return accessRows(userIds, now, true, true);
    }
    // tenancy:end

    // tenancy:begin
    private List<AccessRow> accessRows(Collection<UUID> userIds, Instant now, boolean activeOnly, boolean anyTenantStatus) {
    // tenancy:else
    // private List<AccessRow> accessRows(Collection<UUID> userIds, Instant now, boolean activeOnly) {
    // tenancy:end
        if (userIds.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource("userIds", userIds).addValue("now", Timestamp.from(now))
                .addValue("activeOnly", activeOnly);
        // tenancy:begin
        parameters.addValue("tenantId", TenantContext.id()).addValue("anyTenantStatus", anyTenantStatus);
        // tenancy:end
        return jdbc.query(ACCESS_ROWS, parameters, RoleAssignmentRepositoryImpl::accessRow);
    }

    @Override
    public List<UUID> findHoldersForGood(Collection<String> permissions) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("permissions", permissions);
        // tenancy:begin
        parameters.addValue("tenantId", TenantContext.id());
        // tenancy:end
        return jdbc.queryForList(HOLDERS_FOR_GOOD, parameters, UUID.class);
    }

    private static AccessRow accessRow(ResultSet row, int number) throws SQLException {
        String effect = row.getString("effect");
        Timestamp validUntil = row.getTimestamp("valid_until");
        return new AccessRow(row.getObject("user_id", UUID.class), row.getObject("role_id", UUID.class),
                row.getString("role_code"), row.getString("permission"),
                Objects.isNull(effect) ? null : GrantEffect.valueOf(effect),
                Objects.isNull(validUntil) ? null : validUntil.toInstant());
    }
}
