package com.example.app.user.dto.request;

import com.example.app.common.limits.MaxLength;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Finding an account before changing it: the status change takes the account's id and the version read
 * here. By the exact address only (no search, no list of every account): the address is unique.
 */
@Getter
@Setter
public class UserFilter {

    @NotBlank
    @MaxLength("user.email")
    @Schema(description = "The account's email address, ignoring case and surrounding spaces", example = "asha@example.com")
    private String email;
}
