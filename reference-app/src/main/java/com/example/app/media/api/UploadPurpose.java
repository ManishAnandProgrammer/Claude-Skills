package com.example.app.media.api;

import com.example.app.common.security.Permission;

import java.util.Set;
import java.util.function.Predicate;

public enum UploadPurpose {
    // example:begin
    TODO_ATTACHMENT(Permission.TODO_CREATE_OWN, Permission.TODO_EDIT_OWN),
    // example:end
    ;

    private final Set<Permission> permissions;

    UploadPurpose(Permission... permissions) {
        this.permissions = Set.of(permissions);
    }

    public Set<Permission> permissions() {
        return permissions;
    }

    public boolean mayUpload(Predicate<Permission> callerHolds) {
        return permissions.stream().anyMatch(callerHolds);
    }
}
