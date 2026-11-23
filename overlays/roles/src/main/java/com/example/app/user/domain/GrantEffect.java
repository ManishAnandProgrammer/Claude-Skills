package com.example.app.user.domain;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(enumAsRef = true, description = "ALLOW adds the permission to what the roles give; DENY removes it, whatever gives it")
public enum GrantEffect {
    ALLOW,
    DENY
}
