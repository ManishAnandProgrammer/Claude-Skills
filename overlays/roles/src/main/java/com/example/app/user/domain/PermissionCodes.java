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

/** A table can hold a code no {@link Permission} names (one removed since, or written by hand): it grants nothing. */
@Slf4j
public final class PermissionCodes {

    private static final Map<String, Permission> BY_CODE =
            Arrays.stream(Permission.values()).collect(Collectors.toUnmodifiableMap(Permission::name, Function.identity()));
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private PermissionCodes() {
    }

    public static Optional<Permission> known(String code) {
        Permission permission = BY_CODE.get(code);
        if (Objects.isNull(permission) && LOGGED.add(code)) {
            log.warn("A role or a personal grant names an unknown permission, which grants nothing: code={}", code);
        }
        return Optional.ofNullable(permission);
    }
}
