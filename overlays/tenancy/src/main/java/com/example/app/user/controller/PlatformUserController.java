package com.example.app.user.controller;

import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.user.controller.api.PlatformUserApi;
import com.example.app.user.dto.request.UserStatusRequest;
import com.example.app.user.dto.response.UserResponse;
import com.example.app.user.service.AccountStatusService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Control plane: only platform tokens reach {@code /platform/**} (PlatformSecurityConfig's chain). */
@RestController
@RequestMapping("/api/{version}/platform/users")
@RequiredArgsConstructor
class PlatformUserController implements PlatformUserApi {

    private final AccountStatusService accountStatus;

    @Override
    @PatchMapping(path = "/{id}/status", version = "v1+")
    public ObjectWrapper<UserResponse> changeStatus(@PathVariable UUID id, @RequestBody @Valid UserStatusRequest request) {
        return accountStatus.changeStatus(id, request);
    }
}
