package com.example.app.user.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = """
        A person's access: their roles and personal grants as given, and what they hold now with where each permission \
        comes from (so an admin can answer "why can she do this"), and what a DENY or a missing prerequisite took away""")
public record PersonAccessResponse(
        @Schema(description = "The person", example = "0190a9c2-6c43-7cc1-9f2a-1d1e5a6b7c8d") UUID userId,
        @Schema(description = "Their roles, by code, expired ones included until removed") List<AssignedRoleResponse> roles,
        @Schema(description = "Their personal grants, by permission, expired ones included until removed") List<PersonalGrantResponse> grants,
        @Schema(description = "What they hold now, in the Permission enum's order") List<HeldPermissionResponse> held,
        @Schema(description = "What their roles or ALLOWs give that they don't hold now") List<RemovedPermissionResponse> removed) {

    public PersonAccessResponse {
        roles = List.copyOf(roles);
        grants = List.copyOf(grants);
        held = List.copyOf(held);
        removed = List.copyOf(removed);
    }
}
