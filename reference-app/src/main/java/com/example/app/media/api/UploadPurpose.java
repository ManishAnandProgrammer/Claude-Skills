package com.example.app.media.api;

/**
 * What an upload is for, and who may upload for it. A new purpose is a constant here (something in
 * code attaches its files) plus its {@code upload_policies} row in a changeset (row-mode tenancy: the
 * module's {@code TenantProvisioned} listener seeds each tenant's); its limits are data.
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

    private final String role;

    UploadPurpose(String role) {
        this.role = role;
    }

    /** The role (the token's {@code roles} claim) that may upload for this purpose. */
    public String role() {
        return role;
    }
}
