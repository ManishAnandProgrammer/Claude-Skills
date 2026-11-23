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

/** A permission whose prerequisite isn't held is dropped too: a prerequisite is never granted for it. */
public final class EffectiveAccess {

    public record Granted(Permission permission, List<String> roleCodes, boolean personallyAllowed) {

        public Granted {
            roleCodes = List.copyOf(roleCodes);
        }
    }

    @Schema(enumAsRef = true, description = "DENIED (a personal DENY) or PREREQUISITE_MISSING (one it needs isn't held)")
    public enum Removal { DENIED, PREREQUISITE_MISSING }

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

    /** The read returns current rows only, so every row here gives or takes now. */
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
     * Every current DENY still takes away, whatever its end: the last-manager check counts these holders, so an
     * expiring grant is never the last one of a permission that must stay held.
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

    public Set<Permission> permissions() {
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        permissions.addAll(granted.keySet());
        return permissions;
    }

    public boolean holds(Permission permission) {
        return granted.containsKey(permission);
    }

    public List<Granted> granted() {
        return List.copyOf(granted.values());
    }

    public List<Removed> removed() {
        return List.copyOf(removed);
    }

    public String describe() {
        return "roles %s, allowed %s, denied %s, held %s".formatted(roleCodes, allowed, denied, permissions());
    }
}
