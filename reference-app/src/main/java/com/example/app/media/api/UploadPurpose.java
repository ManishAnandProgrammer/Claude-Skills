package com.example.app.media.api;

import com.example.app.common.security.Permission;

import java.util.Set;
import java.util.function.Predicate;

/**
 * What an upload is for, and who may upload for it. A new purpose is a constant here (something in
 * code attaches its files) plus its {@code upload_policies} row in a changeset (row-mode tenancy: the
 * module's {@code TenantProvisioned} listener seeds each new tenant's when missing, and the changeset's
 * row-mode block seeds the tenants that exist when it ships, as the todo's 0010 does); its limits are data.
 *
 * <p>The permission is checked when a URL is issued ({@code POST /upload-urls}), because the purpose comes
 * in the request body: no annotation can name it. The owning record's write checks its own rules
 * (whose record, is it editable) when it attaches.</p>
 */
public enum UploadPurpose {
    // example:begin
    /** A todo's attachments (the todo module): for a todo's create and its edit. */
    TODO_ATTACHMENT(Permission.TODO_CREATE_OWN, Permission.TODO_EDIT_OWN),
    // example:end
    ;

    private final Set<Permission> permissions;

    UploadPurpose(Permission... permissions) {
        this.permissions = Set.of(permissions);
    }

    /**
     * The permissions that may upload for this purpose: the writes of the owning record that attach its files.
     * A caller holding any one of them may upload; the owning record's write still checks that record.
     */
    public Set<Permission> permissions() {
        return permissions;
    }

    /**
     * Whether a caller may upload for this purpose: they hold one of its permissions
     * ({@code CurrentUser::holds}). Here rather than in the service, so a unit test reaches the refusal
     * even when every role holds the purpose's permissions.
     */
    public boolean mayUpload(Predicate<Permission> callerHolds) {
        return permissions.stream().anyMatch(callerHolds);
    }
}
