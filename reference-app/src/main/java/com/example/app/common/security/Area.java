package com.example.app.common.security;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(enumAsRef = true, description = "A top-level menu item: listed when the caller holds one of its permissions")
public enum Area {
    // example:begin
    TODOS,
    // example:end
    // sign-in:begin
    ACCOUNTS,
    // sign-in:end
    // metadata:begin
    SETTINGS,
    // metadata:else
    // // media:begin
    // SETTINGS,
    // // media:end
    // metadata:end
}
