package com.example.app.tenant.dto.request;

import com.example.app.common.limits.MaxLength;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "A new tenant and its owner")
public record TenantCreateRequest(
        @Schema(description = "The organisation's name", example = "Acme Ltd")
        @NotBlank @MaxLength("tenant.name")
        String name,

        @Schema(description = "The owner's account email; the owner becomes the tenant's ADMIN", example = "asha@example.com")
        @NotBlank @Email @MaxLength("user.email")
        String ownerEmail) {
}
