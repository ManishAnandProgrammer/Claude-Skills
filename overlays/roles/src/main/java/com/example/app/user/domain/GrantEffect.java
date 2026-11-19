package com.example.app.user.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** What a personal grant does to one permission: adds it, or takes it away whatever the person's roles give. */
@Schema(enumAsRef = true, description = "ALLOW adds the permission to what the roles give; DENY removes it, whatever gives it")
public enum GrantEffect {
    ALLOW,
    DENY
}
