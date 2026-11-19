package com.example.app.common.security;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A top-level item of the client's menu. {@code GET /users/me/access} lists an area when the caller holds at
 * least one of its permissions ({@link Permission#area()}), so a client shows a menu item by its area and never
 * decides which permissions make one; the labels, order and icons stay the client's.
 */
@Schema(enumAsRef = true, description = "A top-level menu item: listed when the caller holds one of its permissions")
public enum Area {
    // example:begin
    /** Todo lists, todos, tags and plans. */
    TODOS,
    // example:end
    // sign-in:begin
    /** Other people's accounts (without tenancy) or the tenant's members (with it). */
    ACCOUNTS,
    // sign-in:end
    // metadata:begin
    /** Application-wide settings (configuration metadata, upload policies, as the pieces have them). */
    SETTINGS,
    // metadata:else
    // // media:begin
    // /** Application-wide settings: upload policies. */
    // SETTINGS,
    // // media:end
    // metadata:end
}
