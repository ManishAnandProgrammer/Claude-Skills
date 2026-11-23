package com.example.app.user.repository.impl;

// tenancy:begin
// tenancy:else
// import com.example.app.common.dropdown.DropdownCandidate;
// import com.example.app.common.dropdown.DropdownFilter;
// import com.example.app.common.dropdown.DropdownItem;
// import com.example.app.common.pagination.Cursor;
// import com.example.app.common.pagination.CursorBatch;
// import com.example.app.user.api.PermissionHolders;
// tenancy:end
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
// tenancy:begin
// tenancy:else
// import java.util.Locale;
// tenancy:end
import java.util.Objects;
import java.util.UUID;

/**
 * Plain JDBC: Hibernate's statistics, which the tests' statement counts read, never see this per-request read.
 *   <!-- tenancy:begin -->
 * The account, membership and tenant are joined in the same statement, so a suspended account, a removed member or a
 * suspended tenant holds nothing on its next request.
 *   <!-- tenancy:end -->
 */
@Repository
@RequiredArgsConstructor
class RoleAssignmentRepositoryImpl implements CustomRoleAssignmentRepository {

    // tenancy-schema:begin
    // The platform's tables are named with their schema: a tenant's transaction has its own schema alone on the
    // search path (TenancyArchTest allows this one class).
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
    // person serve it.
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

    // tenancy:begin
    // tenancy:else
    //
    // // Every needed permission held now (a role or an ALLOW) and none denied: EffectiveAccess's rule, prerequisites
    // // included by the caller. The keyset runs on idx_users_full_name_id; the access is checked per row it reads.
    // static final String HOLDERS = """
    //         SELECT u.id, u.full_name
    //           FROM users u
    //          WHERE u.status = 'ACTIVE'
    //            AND (SELECT count(DISTINCT held.permission)
    //                   FROM (SELECT p.permission
    //                           FROM user_roles a
    //                           JOIN role_permissions p ON p.role_id = a.role_id
    //                          WHERE a.user_id = u.id AND p.permission IN (:permissions)
    //                            AND (a.valid_until IS NULL OR a.valid_until > :now)
    //                         UNION ALL
    //                         SELECT g.permission
    //                           FROM user_permissions g
    //                          WHERE g.user_id = u.id AND g.permission IN (:permissions) AND g.effect = 'ALLOW'
    //                            AND (g.valid_until IS NULL OR g.valid_until > :now)) held) = :needed
    //            AND NOT EXISTS (SELECT 1
    //                              FROM user_permissions g
    //                             WHERE g.user_id = u.id AND g.permission IN (:permissions) AND g.effect = 'DENY'
    //                               AND (g.valid_until IS NULL OR g.valid_until > :now))
    //            AND (CAST(:query AS text) IS NULL OR position(CAST(:query AS text) IN lower(u.full_name)) > 0)
    //         """;
    // static final String HOLDERS_ASC = HOLDERS + "ORDER BY u.full_name, u.id LIMIT :limit";
    // static final String HOLDERS_ASC_AFTER = HOLDERS + "AND (u.full_name, u.id) > (:name, :id) ORDER BY u.full_name, u.id LIMIT :limit";
    // static final String HOLDERS_DESC = HOLDERS + "ORDER BY u.full_name DESC, u.id DESC LIMIT :limit";
    // static final String HOLDERS_DESC_AFTER = HOLDERS
    //         + "AND (u.full_name, u.id) < (:name, :id) ORDER BY u.full_name DESC, u.id DESC LIMIT :limit";
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
    public List<AccessRow> findAccessRowsWhateverTheAccountStatus(Collection<UUID> userIds, Instant now) {
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

    // tenancy:begin
    // tenancy:else
    // @Override
    // public CursorBatch<DropdownItem> findHolders(Collection<String> permissions, DropdownFilter filter, int size, Instant now) {
    //     boolean descending = filter.getSortDirection() == DropdownCandidate.SortDirection.DESC;
    //     String sort = PermissionHolders.sortOf(filter);
    //     Cursor cursor = Cursor.read(filter.getAfter(), sort, PermissionHolders.CURSOR_KEYS);
    //     MapSqlParameterSource parameters = new MapSqlParameterSource("permissions", permissions)
    //             .addValue("needed", permissions.size()).addValue("now", Timestamp.from(now)).addValue("limit", size + 1)
    //             .addValue("query", filter.requireQueryPredicate() ? filter.getQuery().strip().toLowerCase(Locale.ROOT) : null);
    //     String statement = descending ? HOLDERS_DESC : HOLDERS_ASC;
    //     if (cursor.hasPosition()) {
    //         statement = descending ? HOLDERS_DESC_AFTER : HOLDERS_ASC_AFTER;
    //         parameters.addValue("name", cursor.text(0)).addValue("id", cursor.uuid(1));
    //     }
    //     List<DropdownItem> rows = jdbc.query(statement, parameters,
    //             (row, number) -> new DropdownItem(row.getObject("id", UUID.class), row.getString("full_name")));
    //     return CursorBatch.of(rows, size, item -> Cursor.write(sort, item.label(), item.value()));
    // }
    // tenancy:end

    private static AccessRow accessRow(ResultSet row, int number) throws SQLException {
        String effect = row.getString("effect");
        Timestamp validUntil = row.getTimestamp("valid_until");
        return new AccessRow(row.getObject("user_id", UUID.class), row.getObject("role_id", UUID.class),
                row.getString("role_code"), row.getString("permission"),
                Objects.isNull(effect) ? null : GrantEffect.valueOf(effect),
                Objects.isNull(validUntil) ? null : validUntil.toInstant());
    }
}
