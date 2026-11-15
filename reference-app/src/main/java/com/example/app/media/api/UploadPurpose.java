package com.example.app.media.api;

import java.util.List;
import java.util.function.Predicate;

/**
 * What an upload is for, and who may upload for it. A new purpose is a constant here (something in
 * code attaches its files) plus its {@code upload_policies} row in a changeset (row-mode tenancy: the
 * module's {@code TenantProvisioned} listener seeds each new tenant's when missing, and the changeset's
 * row-mode block seeds the tenants that exist when it ships, as the todo's 0010 does); its limits are data.
 *
 * <p>The role is checked when a URL is issued ({@code POST /upload-urls}), because the purpose comes
 * in the request body: no annotation can name it. The owning record's write checks its own rules
 * (whose record, is it editable) when it attaches.</p>
 */
public enum UploadPurpose {
    // example:begin
    /** A todo's attachments (the todo module). */
    TODO_ATTACHMENT("USER"),
    // example:end
    ;

    private final List<String> roles;

    UploadPurpose(String... roles) {
        this.roles = List.of(roles);
    }

    /**
     * The roles (the token's {@code roles} claim) that may upload for this purpose: every role that
     * writes the owning record. Roles don't include each other (no role hierarchy), so a record that
     * USER members and their tenant's ADMIN both edit names both ({@code STUDENT_PHOTO("USER", "ADMIN")}).
     */
    public List<String> roles() {
        return roles;
    }

    /**
     * Whether a caller may upload for this purpose: one of the purpose's roles is one the caller holds
     * ({@code CurrentUser::hasRole}). Here rather than in the service, so a unit test reaches the refusal
     * even when a purpose names every role there is.
     */
    public boolean mayUpload(Predicate<String> callerHasRole) {
        return roles.stream().anyMatch(callerHasRole);
    }
}
