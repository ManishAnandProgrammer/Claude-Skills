package com.example.app.user.domain;

import com.example.app.common.security.Permission;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * What one person may do (the roles piece, house-review AUTHZ-11): the permissions of their current roles, plus
 * their current personal ALLOWs, minus their current personal DENYs. A deny always wins; roles only allow. A
 * permission whose prerequisite isn't held (a DENY took it, or nothing gave it) is dropped too, until nothing
 * changes: a prerequisite is never granted for it. An unknown code in a row grants nothing. Computed from the
 * rows of one read, so every check of a request and {@code GET /users/me/access} see the same set.
 */
public final class EffectiveAccess {

    /** A held permission and where it comes from: the codes of the roles that give it (sorted), and a personal ALLOW. */
    public record Granted(Permission permission, List<String> roleCodes, boolean personallyAllowed) {

        public Granted {
            roleCodes = List.copyOf(roleCodes);
        }
    }

    /** Why a permission something gave is not held. */
    @Schema(enumAsRef = true, description = "DENIED (a personal DENY) or PREREQUISITE_MISSING (one it needs isn't held)")
    public enum Removal { DENIED, PREREQUISITE_MISSING }

    /** A permission something gave but the person doesn't hold, and why ({@code missing}: the prerequisites not held). */
    public record Removed(Permission permission, Removal reason, Set<Permission> missing) {

        public Removed {
            missing = Set.copyOf(missing);
        }
    }

    private final Map<Permission, Granted> granted;
    private final List<Removed> removed;
    private final Set<String> roleCodes;
    private final Set<Permission> allowed;
    private final Set<Permission> denied;

    private EffectiveAccess(Map<Permission, Granted> granted, List<Removed> removed, Set<String> roleCodes,
                            Set<Permission> allowed, Set<Permission> denied) {
        this.granted = granted;
        this.removed = removed;
        this.roleCodes = roleCodes;
        this.allowed = allowed;
        this.denied = denied;
    }

    /** The access the rows of one person give now (the read returns current rows only). */
    public static EffectiveAccess of(Collection<AccessRow> rows) {
        Map<Permission, Set<String>> fromRoles = new EnumMap<>(Permission.class);
        Set<String> roleCodes = new TreeSet<>();
        Set<Permission> allowed = EnumSet.noneOf(Permission.class);
        Set<Permission> denied = EnumSet.noneOf(Permission.class);
        for (AccessRow row : rows) {
            Optional<Permission> known = PermissionCodes.known(row.permission());
            if (known.isEmpty()) {
                continue;
            }
            if (!row.isPersonal()) {
                roleCodes.add(row.roleCode());
                fromRoles.computeIfAbsent(known.get(), permission -> new TreeSet<>()).add(row.roleCode());
            } else if (row.effect() == GrantEffect.ALLOW) {
                allowed.add(known.get());
            } else {
                denied.add(known.get());
            }
        }
        Set<Permission> held = EnumSet.noneOf(Permission.class);
        held.addAll(fromRoles.keySet());
        held.addAll(allowed);
        List<Removed> removed = new ArrayList<>();
        for (Permission permission : EnumSet.copyOf(held)) {
            if (denied.contains(permission)) {
                held.remove(permission);
                removed.add(new Removed(permission, Removal.DENIED, Set.of()));
            }
        }
        dropThoseMissingAPrerequisite(held, removed);
        Map<Permission, Granted> granted = new EnumMap<>(Permission.class);
        for (Permission permission : held) {
            List<String> givenBy = List.copyOf(fromRoles.getOrDefault(permission, Set.of()));
            granted.put(permission, new Granted(permission, givenBy, allowed.contains(permission)));
        }
        return new EffectiveAccess(granted, removed, roleCodes, allowed, denied);
    }

    /**
     * What the rows give for good: only the grants without an end (a role assignment or an ALLOW until a date will
     * stop giving), every current DENY still taking away. Safety rule 6 counts these holders, so an expiring grant
     * is never the last one of a permission that must stay held.
     */
    public static EffectiveAccess forGoodOf(Collection<AccessRow> rows) {
        List<AccessRow> lasting = new ArrayList<>();
        for (AccessRow row : rows) {
            if (row.isForGood() || row.effect() == GrantEffect.DENY) {
                lasting.add(row);
            }
        }
        return of(lasting);
    }

    /** Removes, until nothing changes, every held permission one of whose prerequisites isn't held. */
    private static void dropThoseMissingAPrerequisite(Set<Permission> held, List<Removed> removed) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Permission permission : EnumSet.copyOf(held)) {
                Set<Permission> missing = EnumSet.noneOf(Permission.class);
                missing.addAll(permission.prerequisites());
                missing.removeAll(held);
                if (!missing.isEmpty()) {
                    held.remove(permission);
                    removed.add(new Removed(permission, Removal.PREREQUISITE_MISSING, missing));
                    changed = true;
                }
            }
        }
    }

    /** The permissions held, in the enum's order. */
    public Set<Permission> permissions() {
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        permissions.addAll(granted.keySet());
        return permissions;
    }

    public boolean holds(Permission permission) {
        return granted.containsKey(permission);
    }

    /** Every held permission with its sources, in the enum's order. */
    public List<Granted> granted() {
        return List.copyOf(granted.values());
    }

    /** The permissions something gave but the person doesn't hold, with the reason, in the order they were removed. */
    public List<Removed> removed() {
        return List.copyOf(removed);
    }

    /**
     * The read's inputs and result as codes, for the authorization log's TRACE line: the roles that gave a
     * permission, the personal ALLOWs and DENYs (known codes only, as the read counts them), and what is held.
     */
    public String describe() {
        return "roles %s, allowed %s, denied %s, held %s".formatted(roleCodes, allowed, denied, permissions());
    }
}
