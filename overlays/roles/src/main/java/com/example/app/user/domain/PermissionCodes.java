package com.example.app.user.domain;

import com.example.app.common.security.Permission;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The permission a code in a table names (the roles piece stores codes as text the application checks): a code
 * that names no {@link Permission} constant (one removed since, or written by hand) grants nothing, and is
 * logged once per code, not once per request.
 */
@Slf4j
public final class PermissionCodes {

    private static final Map<String, Permission> BY_CODE =
            Arrays.stream(Permission.values()).collect(Collectors.toUnmodifiableMap(Permission::name, Function.identity()));
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private PermissionCodes() {
    }

    /** The permission {@code code} names; empty for an unknown one, which fails closed. */
    public static Optional<Permission> known(String code) {
        Permission permission = BY_CODE.get(code);
        if (Objects.isNull(permission) && LOGGED.add(code)) {
            log.warn("A role or a personal grant names an unknown permission, which grants nothing: code={}", code);
        }
        return Optional.ofNullable(permission);
    }
}
